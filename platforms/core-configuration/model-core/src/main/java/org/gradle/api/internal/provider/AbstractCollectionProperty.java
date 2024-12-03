/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.provider;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.gradle.api.Action;
import org.gradle.api.Transformer;
import org.gradle.api.internal.provider.Collectors.ElementFromProvider;
import org.gradle.api.internal.provider.Collectors.ElementsFromArray;
import org.gradle.api.internal.provider.Collectors.ElementsFromCollection;
import org.gradle.api.internal.provider.Collectors.ElementsFromCollectionProvider;
import org.gradle.api.internal.provider.Collectors.SingleElement;
import org.gradle.api.provider.HasMultipleValues;
import org.gradle.api.provider.Provider;
import org.gradle.internal.Cast;
import org.gradle.internal.evaluation.EvaluationScopeContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The base class for collection properties.
 * <p>
 * Value suppliers for collection properties are implementations of {@link CollectionSupplier}.
 * </p>
 * <p>
 * Elements stored in collection property values are implemented via various implementations of {@link Collector}.
 * </p>
 * <h2>Collection suppliers</h2>
 * The value of a collection property is represented at any time as an instance of an implementation of {@link CollectionSupplier}, namely:
 * <ul>
 *     <li>{@link EmptySupplier}, the initial value of a collection (or after {@link #empty()} is invoked)</li>
 *     <li>{@link NoValueSupplier}, when the collection value is unset (via {@link #set(Iterable)} or {@link #unset()}.</li>
 *     <li>{@link FixedSupplier}, when the collection is finalized - in that case, the fixed supplier will wrap the realized
 *     of the Java collection this collection property corresponds to</li>
 *     <li>{@link CollectingSupplier}, when the collection is still being added to - in that case,
 *     the collecting supplier will wrap a {@link Collector} that lazily represents the yet-to-be realized contents of the collection - see below for details</li>
 * </ul>
 *
 * <h2>Collectors</h2>
 * <p>
 *     While a collection property's contents are being built up, its value is represented by a {@link CollectingSupplier}.
 *     The collecting supplier will wrap a {@link Collector} instance that represents the various forms that elements can be added to a collection property (before the collection is finalized), namely:
 * </p>
 *     <ul>
 *         <li>{@link SingleElement} to represent a single element addition
 *         <li>{@link ElementFromProvider} to represent a single element added as a provider
 *         <li>{@link ElementsFromArray} to represent a single element added as an array</li>
 *         <li>{@link ElementsFromCollection} to represent a batch of elements added (or set wholesale) as an <code>Iterable</code>
 *         <li>{@link ElementsFromCollectionProvider} to represent a batch of elements added (or set wholesale) as a provider of <code>Iterable</code>
 *     </ul>
 * <p>Also, if a collection is built up via multiple additions, which is quite common, after each addition operation, its value will be represented via a new {@link PlusCollector} instance
 * that references the previous value as the {@link PlusCollector#left left side}, and the added element(s) as {@link PlusCollector#right right side} of the operation.
 * </p>
 *
 * @param <T> the type of element this collection property can hold
 * @param <C> the type of {@link Collection} (as returned by {@link ProviderInternal#getType()}) that corresponds to this collection property's realized value, for instance, when {@link Provider#get()} is invoked.
 */
public abstract class AbstractCollectionProperty<T, C extends Collection<T>> extends AbstractProperty<C, CollectionSupplier<T, C>>
    implements CollectionPropertyInternal<T, C> {

    private final Class<? extends Collection> collectionType;
    private final Class<T> elementType;
    private final Supplier<ImmutableCollection.Builder<T>> collectionFactory;
    private final ValueCollector<T> valueCollector;
    private CollectionSupplier<T, C> defaultValue;

    AbstractCollectionProperty(PropertyHost host, Class<? extends Collection> collectionType, Class<T> elementType, Supplier<ImmutableCollection.Builder<T>> collectionFactory) {
        super(host);
        this.collectionType = collectionType;
        this.elementType = elementType;
        this.collectionFactory = collectionFactory;
        valueCollector = new ValidatingValueCollector<>(collectionType, elementType, ValueSanitizers.forType(elementType));
        init();
    }

    private void init() {
        defaultValue = emptySupplier();
        init(defaultValue, noValueSupplier());
    }

    @Override
    protected CollectionSupplier<T, C> getDefaultValue() {
        return defaultValue;
    }

    @Override
    protected CollectionSupplier<T, C> getDefaultConvention() {
        return noValueSupplier();
    }

    private CollectionSupplier<T, C> emptySupplier() {
        return new EmptySupplier();
    }

    private CollectionSupplier<T, C> noValueSupplier() {
        return new NoValueSupplier(Value.missing());
    }

    /**
     * Creates an empty immutable collection.
     */
    protected abstract C emptyCollection();

    protected Configurer getConfigurer(boolean ignoreAbsent) {
        return new Configurer(ignoreAbsent);
    }

    protected void withActualValue(Action<Configurer> action) {
        setToConventionIfUnset();
        action.execute(getConfigurer(true));
    }

    @Override
    protected boolean isDefaultConvention() {
        return isNoValueSupplier(getConventionSupplier());
    }

    private boolean isNoValueSupplier(CollectionSupplier<T, C> valueSupplier) {
        // Cannot use plain NoValueSupplier because of Java restrictions:
        // a generic type [AbstractCollectionProperty<T, C>.]NoValueSupplier cannot be used in instanceof.
        return valueSupplier instanceof AbstractCollectionProperty<?, ?>.NoValueSupplier;
    }

    @Override
    public void add(final T element) {
        getConfigurer(false).add(element);
    }

    @Override
    public void add(final Provider<? extends T> providerOfElement) {
        getConfigurer(false).add(providerOfElement);
    }

    @Override
    @SafeVarargs
    @SuppressWarnings("varargs")
    public final void addAll(T... elements) {
        getConfigurer(false).addAll(elements);
    }

    @Override
    public void addAll(Iterable<? extends T> elements) {
        getConfigurer(false).addAll(elements);
    }

    @Override
    public void addAll(Provider<? extends Iterable<? extends T>> provider) {
        getConfigurer(false).addAll(provider);
    }

    @Override
    public void append(T element) {
        withActualValue(it -> it.add(element));
    }

    @Override
    public void append(Provider<? extends T> provider) {
        withActualValue(it -> it.add(provider));
    }

    @Override
    @SuppressWarnings("varargs")
    @SafeVarargs
    public final void appendAll(T... elements) {
        withActualValue(it -> it.addAll(elements));
    }

    @Override
    public void appendAll(Iterable<? extends T> elements) {
        withActualValue(it -> it.addAll(elements));
    }

    @Override
    public void appendAll(Provider<? extends Iterable<? extends T>> provider) {
        withActualValue(it -> it.addAll(provider));
    }

    @Override
    public int size() {
        return calculateOwnPresentValue().getWithoutSideEffect().size();
    }

    /**
     * Adds the given supplier as the new root supplier for this collection.
     *
     * @param collector the collector to add
     * @param ignoreAbsent whether elements that are missing values should be ignored
     */
    private void addExplicitCollector(Collector<T> collector, boolean ignoreAbsent) {
        assertCanMutate();
        CollectionSupplier<T, C> explicitValue = getExplicitValue(defaultValue);
        setSupplier(explicitValue.plus(ignoreAbsent ? new AbsentIgnoringCollector<>(collector) : collector));
    }

    @Nullable
    @Override
    public Class<C> getType() {
        return Cast.uncheckedCast(collectionType);
    }

    @Override
    public Class<T> getElementType() {
        return elementType;
    }

    /**
     * Sets the value of this property the given value.
     */
    public void fromState(ExecutionTimeValue<? extends C> value) {
        if (value.isMissing()) {
            setSupplier(noValueSupplier());
        } else if (value.hasFixedValue()) {
            setSupplier(new FixedSupplier(value.getFixedValue(), Cast.uncheckedCast(value.getSideEffect())));
        } else {
            CollectingProvider<T, C> asCollectingProvider = Cast.uncheckedNonnullCast(value.getChangingValue());
            setSupplier(new CollectingSupplier(new ElementsFromCollectionProvider<>(asCollectingProvider)));
        }
    }

    @Override
    public void setFromAnyValue(Object object) {
        if (object instanceof Provider) {
            set(Cast.<Provider<C>>uncheckedCast(object));
        } else {
            if (object != null && !(object instanceof Iterable)) {
                throw new IllegalArgumentException(String.format("Cannot set the value of a property of type %s using an instance of type %s.", collectionType.getName(), object.getClass().getName()));
            }
            set(Cast.<Iterable<? extends T>>uncheckedCast(object));
        }
    }

    @Override
    public void set(@Nullable final Iterable<? extends T> elements) {
        if (elements == null) {
            unsetValueAndDefault();
        } else {
            setSupplier(new CollectingSupplier(new ElementsFromCollection<>(elements)));
        }
    }

    @Override
    public void set(final Provider<? extends Iterable<? extends T>> provider) {
        if (provider == null) {
            throw new IllegalArgumentException("Cannot set the value of a property using a null provider.");
        }
        ProviderInternal<? extends Iterable<? extends T>> p = Providers.internal(provider);
        if (p.getType() != null && !Iterable.class.isAssignableFrom(p.getType())) {
            throw new IllegalArgumentException(String.format("Cannot set the value of a property of type %s using a provider of type %s.", collectionType.getName(), p.getType().getName()));
        }
        if (p instanceof CollectionPropertyInternal) {
            CollectionPropertyInternal<T, C> collectionProp = Cast.uncheckedCast(p);
            if (!elementType.isAssignableFrom(collectionProp.getElementType())) {
                throw new IllegalArgumentException(String.format("Cannot set the value of a property of type %s with element type %s using a provider with element type %s.", collectionType.getName(), elementType.getName(), collectionProp.getElementType().getName()));
            }
        }
        setSupplier(new CollectingSupplier(new ElementsFromCollectionProvider<>(p)));
    }

    private void unsetValueAndDefault() {
        // assign no-value default before restoring to it
        defaultValue = noValueSupplier();
        unset();
    }

    @Override
    public HasMultipleValues<T> value(@Nullable Iterable<? extends T> elements) {
        set(elements);
        return this;
    }

    @Override
    public HasMultipleValues<T> value(Provider<? extends Iterable<? extends T>> provider) {
        set(provider);
        return this;
    }

    @Override
    public HasMultipleValues<T> empty() {
        setSupplier(emptySupplier());
        return this;
    }

    @Override
    protected Value<? extends C> calculateValueFrom(EvaluationScopeContext context, CollectionSupplier<T, C> value, ValueConsumer consumer) {
        return value.calculateValue(consumer);
    }

    @Override
    protected CollectionSupplier<T, C> finalValue(EvaluationScopeContext context, CollectionSupplier<T, C> value, ValueConsumer consumer) {
        Value<? extends C> result = value.calculateValue(consumer);
        if (!result.isMissing()) {
            return new FixedSupplier(result.getWithoutSideEffect(), Cast.uncheckedCast(result.getSideEffect()));
        } else if (result.getPathToOrigin().isEmpty()) {
            return noValueSupplier();
        } else {
            return new NoValueSupplier(result);
        }
    }

    @Override
    protected ExecutionTimeValue<? extends C> calculateOwnExecutionTimeValue(EvaluationScopeContext context, CollectionSupplier<T, C> value) {
        return value.calculateExecutionTimeValue();
    }

    @Override
    public HasMultipleValues<T> convention(@Nullable Iterable<? extends T> elements) {
        if (elements == null) {
            unsetConvention();
        } else {
            setConvention(new CollectingSupplier(new ElementsFromCollection<>(elements)));
        }
        return this;
    }

    @Override
    public HasMultipleValues<T> convention(Provider<? extends Iterable<? extends T>> provider) {
        setConvention(new CollectingSupplier(new ElementsFromCollectionProvider<>(Providers.internal(provider))));
        return this;
    }

    @Override
    protected String describeContents() {
        String typeDisplayName = collectionType.getSimpleName().toLowerCase(Locale.ROOT);
        return String.format("%s(%s, %s)", typeDisplayName, elementType, describeValue());
    }

    class NoValueSupplier implements CollectionSupplier<T, C> {
        private final Value<? extends C> value;

        public NoValueSupplier(Value<? extends C> value) {
            assert value.isMissing();
            this.value = value.asType();
        }

        @Override
        public CollectionSupplier<T, C> absentIgnoring() {
            return Cast.uncheckedCast(emptySupplier());
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return false;
        }

        @Override
        public Value<? extends C> calculateValue(ValueConsumer consumer) {
            return value;
        }

        @Override
        public CollectionSupplier<T, C> plus(Collector<T> collector) {
            // No value + something = no value
            return this;
        }

        @Override
        public ExecutionTimeValue<? extends C> calculateExecutionTimeValue() {
            return ExecutionTimeValue.missing();
        }

        @Override
        public ValueProducer getProducer() {
            return ValueProducer.unknown();
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    private class EmptySupplier implements CollectionSupplier<T, C> {

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return true;
        }

        @Override
        public Value<? extends C> calculateValue(ValueConsumer consumer) {
            return Value.of(emptyCollection());
        }

        @Override
        public CollectionSupplier<T, C> plus(Collector<T> collector) {
            // empty + something = something
            return new CollectingSupplier(collector);
        }

        @Override
        public CollectionSupplier<T, C> absentIgnoring() {
            return this;
        }

        @Override
        public ExecutionTimeValue<? extends C> calculateExecutionTimeValue() {
            return ExecutionTimeValue.fixedValue(emptyCollection());
        }

        @Override
        public ValueProducer getProducer() {
            return ValueProducer.noProducer();
        }

        @Override
        public String toString() {
            return "[]";
        }
    }

    private class FixedSupplier implements CollectionSupplier<T, C> {
        private final C value;
        private final SideEffect<? super C> sideEffect;

        public FixedSupplier(C value, @Nullable SideEffect<? super C> sideEffect) {
            this.value = value;
            this.sideEffect = sideEffect;
        }

        @Override
        public CollectionSupplier<T, C> absentIgnoring() {
            return this;
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return true;
        }

        @Override
        public Value<? extends C> calculateValue(ValueConsumer consumer) {
            return Value.of(value).withSideEffect(sideEffect);
        }

        @Override
        public CollectionSupplier<T, C> plus(Collector<T> collector) {
            return new CollectingSupplier(new FixedValueCollector<T, C>(value, sideEffect)).plus(collector);
        }

        @Override
        public ExecutionTimeValue<? extends C> calculateExecutionTimeValue() {
            return ExecutionTimeValue.fixedValue(value).withSideEffect(sideEffect);
        }

        @Override
        public ValueProducer getProducer() {
            return ValueProducer.unknown();
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    private class CollectingSupplier implements CollectionSupplier<T, C> {
        // TODO - PersistentList?
        private final List<Collector<T>> collectors;
        private final int size;

        public CollectingSupplier(Collector<T> value) {
            this.size = 1;
            collectors = new ArrayList<>();
            collectors.add(value);
        }

        public CollectingSupplier(List<Collector<T>> collectors, int size) {
            this.collectors = collectors;
            this.size = size;
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            for (Collector<T> collector : Lists.reverse(getCollectors())) {
                if (collector.calculatePresence(consumer)) {
                    if (collector instanceof AbsentIgnoringCollector<?>) {
                        return true;
                    }
                } else {
                    return false;
                }
            }
            return true;
        }

        @Override
        public Value<C> calculateValue(ValueConsumer consumer) {
            // TODO - don't make a copy when the collector already produces an immutable collection
            ImmutableCollection.Builder<T> builder = collectionFactory.get();
            Value<Void> compositeResult = Value.present();
            for (Collector<T> collector : getCollectors()) {
                Value<Void> result = collector.collectEntries(consumer, valueCollector, builder);
                if (result.isMissing()) {
                    builder = collectionFactory.get();
                    compositeResult = result;
                } else if (compositeResult.isMissing()) {
                    if (collector instanceof AbsentIgnoringCollector<?>) {
                        compositeResult = result;
                    } else {
                        builder = collectionFactory.get();
                    }
                } else {
                    compositeResult = compositeResult.withSideEffect(SideEffect.fixedFrom(result));
                }
            }
            if (compositeResult.isMissing()) {
                return compositeResult.asType();
            }
            return Value.of(Cast.<C>uncheckedNonnullCast(builder.build())).withSideEffect(SideEffect.fixedFrom(compositeResult));
        }

        @Override
        public CollectionSupplier<T, C> plus(Collector<T> addedCollector) {
            Preconditions.checkState(collectors.size() == size);
            collectors.add(addedCollector);
            return new CollectingSupplier(collectors, size + 1);
        }

        @Override
        public CollectionSupplier<T, C> absentIgnoring() {
            return this;
        }

        @Override
        public ExecutionTimeValue<? extends C> calculateExecutionTimeValue() {
            List<ExecutionTimeValue<? extends Iterable<? extends T>>> values = collectExecutionTimeValues();
            if (values.isEmpty()) {
                return ExecutionTimeValue.missing();
            }
            boolean fixed = true;
            boolean changingContent = false;
            for (ExecutionTimeValue<? extends Iterable<? extends T>> value : values) {
                if (value.isMissing()) {
                    return ExecutionTimeValue.missing();
                }
                if (value.isChangingValue()) {
                    fixed = false;
                } else if (value.hasChangingContent()) {
                    changingContent = true;
                }
            }

            if (fixed) {
                return getFixedExecutionTimeValue(values, changingContent);
            }

            // At least one of the values is a changing value
            List<ProviderInternal<? extends Iterable<? extends T>>> providers = new ArrayList<>(values.size());
            for (ExecutionTimeValue<? extends Iterable<? extends T>> value : values) {
                providers.add(value.toProvider());
            }
            // TODO - CollectionSupplier could be replaced with ProviderInternal, so this type and the collection provider can be merged
            return ExecutionTimeValue.changingValue(new CollectingProvider<>(AbstractCollectionProperty.this.getType(), providers, collectionFactory));
        }

        @Nonnull
        private List<Collector<T>> getCollectors() {
            return collectors.subList(0, size);
        }

        @Nonnull
        private List<ExecutionTimeValue<? extends Iterable<? extends T>>> collectExecutionTimeValues() {
            List<ExecutionTimeValue<? extends Iterable<? extends T>>> executionTimeValues = new ArrayList<>();
            List<ExecutionTimeValue<? extends Iterable<? extends T>>> candidates = new ArrayList<>();

            for (Collector<T> collector : Lists.reverse(getCollectors())) {
                ExecutionTimeValue<? extends Iterable<? extends T>> result = collector.calculateExecutionTimeValue();
                if (result.isMissing()) {
                    return Lists.reverse(executionTimeValues);
                }
                if (collector instanceof AbsentIgnoringCollector<?>) {
                    executionTimeValues.addAll(candidates);
                    executionTimeValues.add(result);
                    candidates.clear();
                } else {
                    candidates.add(result);
                }
            }
            executionTimeValues.addAll(candidates);
            return Lists.reverse(executionTimeValues);
        }

        private ExecutionTimeValue<C> getFixedExecutionTimeValue(List<ExecutionTimeValue<? extends Iterable<? extends T>>> values, boolean changingContent) {
            ImmutableCollection.Builder<T> builder = collectionFactory.get();
            SideEffectBuilder<C> sideEffectBuilder = SideEffect.builder();
            for (ExecutionTimeValue<? extends Iterable<? extends T>> value : values) {
                builder.addAll(value.getFixedValue());
                sideEffectBuilder.add(SideEffect.fixedFrom(value));
            }

            ExecutionTimeValue<C> mergedValue = ExecutionTimeValue.fixedValue(Cast.uncheckedNonnullCast(builder.build()));
            if (changingContent) {
                mergedValue = mergedValue.withChangingContent();
            }

            return mergedValue.withSideEffect(sideEffectBuilder.build());
        }

        @Override
        public ValueProducer getProducer() {
            return getCollectors().stream()
                .map(ValueSupplier::getProducer)
                .reduce(ValueProducer::plus)
                .orElse(ValueProducer.unknown());
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            getCollectors().forEach(collector -> {
                if (sb.length() > 0) {
                    sb.append(" + ");
                }
                sb.append(collector.toString());
            });
            return sb.toString();
        }
    }

    /**
     * A provider for a collection type whose elements are themselves providers.
     */
    private static class CollectingProvider<T, C extends Collection<? extends T>> extends AbstractMinimalProvider<C> {
        private final Class<C> type;
        private final List<ProviderInternal<? extends Iterable<? extends T>>> providers;
        private final Supplier<ImmutableCollection.Builder<T>> collectionFactory;

        public CollectingProvider(Class<C> type, List<ProviderInternal<? extends Iterable<? extends T>>> providers, Supplier<ImmutableCollection.Builder<T>> collectionFactory) {
            this.type = type;
            this.providers = providers;
            this.collectionFactory = collectionFactory;
        }

        @Nullable
        @Override
        public Class<C> getType() {
            return type;
        }

        @Override
        public ExecutionTimeValue<? extends C> calculateExecutionTimeValue() {
            return ExecutionTimeValue.changingValue(this);
        }

        @Override
        protected Value<? extends C> calculateOwnValue(ValueConsumer consumer) {
            ImmutableCollection.Builder<T> builder = collectionFactory.get();
            SideEffectBuilder<? super C> sideEffectBuilder = SideEffect.builder();
            for (ProviderInternal<? extends Iterable<? extends T>> provider : providers) {
                Value<? extends Iterable<? extends T>> value = provider.calculateValue(consumer);
                if (value.isMissing()) {
                    return Value.missing();
                }
                builder.addAll(value.getWithoutSideEffect());
                sideEffectBuilder.add(SideEffect.fixedFrom(value));
            }

            Value<? extends C> resultValue = Value.of(Cast.uncheckedNonnullCast(builder.build()));
            return resultValue.withSideEffect(sideEffectBuilder.build());
        }
    }

    /**
     * A fixed value collector, similar to {@link ElementsFromCollection} but with a side effect.
     */
    private static class FixedValueCollector<T, C extends Collection<T>> implements Collector<T> {
        @Nullable
        private final SideEffect<? super C> sideEffect;
        private final C collection;

        private FixedValueCollector(C collection, @Nullable SideEffect<? super C> sideEffect) {
            this.collection = collection;
            this.sideEffect = sideEffect;
        }

        @Override
        public Value<Void> collectEntries(ValueConsumer consumer, ValueCollector<T> collector, ImmutableCollection.Builder<T> dest) {
            collector.addAll(collection, dest);
            return sideEffect != null
                ? Value.present().withSideEffect(SideEffect.fixed(collection, sideEffect))
                : Value.present();
        }

        @Override
        public int size() {
            return collection.size();
        }

        @Override
        public ExecutionTimeValue<? extends Iterable<? extends T>> calculateExecutionTimeValue() {
            return ExecutionTimeValue.fixedValue(collection).withSideEffect(sideEffect);
        }

        @Override
        public Collector<T> absentIgnoring() {
            // always present
            return this;
        }

        @Override
        public ValueProducer getProducer() {
            return ValueProducer.unknown();
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return true;
        }

        @Override
        public String toString() {
            return collection.toString();
        }
    }

    private static class AbsentIgnoringCollector<T> implements Collector<T> {
        private final Collector<T> delegate;

        private AbsentIgnoringCollector(Collector<T> delegate) {this.delegate = delegate.absentIgnoring();}

        @Override
        public Value<Void> collectEntries(ValueConsumer consumer, ValueCollector<T> collector, ImmutableCollection.Builder<T> dest) {
            ImmutableList.Builder<T> candidateEntries = ImmutableList.builder();
            Value<Void> value = delegate.collectEntries(consumer, collector, candidateEntries);
            if (value.isMissing()) {
                return Value.present();
            }
            dest.addAll(candidateEntries.build());
            return Value.present().withSideEffect(SideEffect.fixedFrom(value));
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public ExecutionTimeValue<? extends Iterable<? extends T>> calculateExecutionTimeValue() {
            ExecutionTimeValue<? extends Iterable<? extends T>> executionTimeValue = delegate.calculateExecutionTimeValue();
            return executionTimeValue.isMissing() ? ExecutionTimeValue.fixedValue(ImmutableList.of()) : executionTimeValue;
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return true;
        }

        @Override
        public ValueProducer getProducer() {
            return delegate.getProducer();
        }

        @Override
        public Collector<T> absentIgnoring() {
            return this;
        }
    }

    public void replace(Transformer<? extends @org.jetbrains.annotations.Nullable Provider<? extends Iterable<? extends T>>, ? super Provider<C>> transformation) {
        Provider<? extends Iterable<? extends T>> newValue = transformation.transform(shallowCopy());
        if (newValue != null) {
            set(newValue);
        } else {
            set((Iterable<? extends T>) null);
        }
    }

    private class Configurer {
        private final boolean ignoreAbsent;

        public Configurer(boolean ignoreAbsent) {
            this.ignoreAbsent = ignoreAbsent;
        }

        protected void addCollector(Collector<T> collector) {
            addExplicitCollector(collector, ignoreAbsent);
        }

        public void add(final T element) {
            Preconditions.checkNotNull(element, "Cannot add a null element to a property of type %s.", collectionType.getSimpleName());
            addCollector(new SingleElement<>(element));
        }

        public void add(final Provider<? extends T> providerOfElement) {
            addCollector(new ElementFromProvider<>(Providers.internal(providerOfElement)));
        }

        @SafeVarargs
        @SuppressWarnings("varargs")
        public final void addAll(T... elements) {
            addCollector(new ElementsFromArray<>(elements));
        }

        public void addAll(Iterable<? extends T> elements) {
            addCollector(new ElementsFromCollection<>(elements));
        }

        public void addAll(Provider<? extends Iterable<? extends T>> provider) {
            addCollector(new ElementsFromCollectionProvider<>(Providers.internal(provider)));
        }

    }
}
