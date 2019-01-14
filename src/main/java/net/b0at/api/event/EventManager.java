package net.b0at.api.event;

import net.b0at.api.event.cache.HandlerEncapsulator;
import net.b0at.api.event.cache.HandlerEncapsulatorWithTiming;
import net.b0at.api.event.exceptions.ListenerAlreadyRegisteredException;
import net.b0at.api.event.exceptions.ListenerNotAlreadyRegisteredException;
import net.b0at.api.event.profiler.IEventProfiler;
import net.b0at.api.event.sorting.HandlerEncapsulatorSorter;
import net.b0at.api.event.types.EventPriority;
import net.b0at.api.event.types.EventTiming;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * The main class that handles {@link EventHandler} (de)registration and {@code event} firing.
 *
 * @param <T> the event base class of this {@link EventManager}
 */
@SuppressWarnings("WeakerAccess")
public final class EventManager<T> {
    /**
     * The {@link Comparator} used to sort {@link HandlerEncapsulator}s based on their {@link EventPriority}.
     */
    private final Comparator<HandlerEncapsulator<T>> ENCAPSULATOR_SORTER = new HandlerEncapsulatorSorter<>();

    /**
     * The active {@link IEventProfiler} for this {@link EventManager}.
     *
     * @see #setEventProfiler(IEventProfiler)
     */
    private IEventProfiler<T> eventProfiler = new IEventProfiler<T>() { };

    /**
     * The complex data structure holding {@link HandlerEncapsulator}s to be invoked.
     *
     * <p>
     * The {@link HandlerEncapsulator}s to be invoked are looked up based on the {@link EventTiming} and
     * the {@link Class} of the event.
     * <br>
     * The {@link HandlerEncapsulator}s are stored in a sorted {@link ConcurrentSkipListSet}.
     * </p>
     *
     * @see #ENCAPSULATOR_SORTER
     */
    protected Map<EventTiming, Map<Class<? extends T>, NavigableSet<HandlerEncapsulator<T>>>> eventEncapsulatorMap = new HashMap<>();

    /**
     * The cache indicating if the {@link EventHandler#persistent()} handlers are registered (TRUE) or not (FALSE OR NULL).
     */
    private Map<Object, Boolean> listenerPersistentStates = new HashMap<>();

    /**
     * The cache indicating if the non {@link EventHandler#persistent()} handlers are registered (TRUE) or not (FALSE OR NULL).
     */
    private Map<Object, Boolean> listenerNonPersistentStates = new HashMap<>();

    /**
     * The cache of the {@link EventHandler#persistent()} {@link HandlerEncapsulator}s of the discovered listeners.
     */
    private Map<Object, Set<HandlerEncapsulator<T>>> persistentCache = new HashMap<>();

    /**
     * The cache of the non {@link EventHandler#persistent()} {@link HandlerEncapsulator}s of the discovered listeners.
     */
    private Map<Object, Set<HandlerEncapsulator<T>>> nonPersistentCache = new HashMap<>();

    /**
     * The number of {@link Method}s currently registered to receive events of all types.
     */
    private int registeredListenerCount = 0;

    /**
     * The event base class of this {@link EventManager}, typically <i>{@link Event}.class</i> or <i>{@link Object}.class</i>.
     */
    protected final Class<T> BASE_CLASS;

    /**
     * Constructs a new {@link EventManager} with {@code BASE_CLASS} as the event base class.
     *
     * @param baseClass the base event class
     */
    public EventManager(Class<T> baseClass) {
        this.BASE_CLASS = baseClass;
        this.eventEncapsulatorMap.put(EventTiming.PRE, new HashMap<>());
        this.eventEncapsulatorMap.put(EventTiming.POST, new HashMap<>());
    }

    /**
     * Registers all applicable {@link EventHandler}s contained in the {@code listener} instance with this {@link EventManager}.
     *
     * @param listener the instance of the {@code listener} to register
     * @param onlyAddPersistent if this is TRUE, only {@link EventHandler#persistent()} are registered,
     *                          if this is FALSE, only non {@link EventHandler#persistent()} are registered
     * @throws ListenerAlreadyRegisteredException if this instance of a {@code listener} is already registered
     */
    public void registerListener(Object listener, boolean onlyAddPersistent) throws ListenerAlreadyRegisteredException {
        Map<Object, Boolean> listenerStates = onlyAddPersistent ? this.listenerPersistentStates : this.listenerNonPersistentStates;
        Boolean state = listenerStates.get(listener);

        if (state == Boolean.TRUE) {
            throw new ListenerAlreadyRegisteredException(listener);
        }

        if (state == null) {
            this.scanListenerForEventHandlers(listener);
        }

        listenerStates.put(listener, Boolean.TRUE);
        this.eventProfiler.onRegisterListener(listener);

        Set<HandlerEncapsulator<T>> encapsulatorSet = onlyAddPersistent ? this.persistentCache.get(listener) : this.nonPersistentCache.get(listener);
        for (HandlerEncapsulator<T> encapsulator : encapsulatorSet) {
            encapsulator.setEnabled(true);
        }
        this.registeredListenerCount += encapsulatorSet.size();
    }

    /**
     * Registers all non {@link EventHandler#persistent()} {@link EventHandler}s contained in the {@code listener} instance with this {@link EventManager}.
     *
     * @see #registerListener(Object, boolean)
     *
     * @param listener the instance of the {@code listener} to register
     * @throws ListenerAlreadyRegisteredException if this instance of a {@code listener} is already registered
     */
    public void registerListener(Object listener) throws ListenerAlreadyRegisteredException{
        this.registerListener(listener, false);
    }

    /**
     * Performs a (very expensive) scan for potential {@link EventHandler}s in the {@code listener} object.
     *
     * <p>
     * The persistent {@link EventHandler}s and the non-persistent {@link EventHandler}s found are stored
     * in {@link #persistentCache} and {@link #nonPersistentCache} respectively.
     * </p>
     *
     * @see #persistentCache
     * @see #nonPersistentCache
     *
     * @param listener the instance of the {@code listener} to scan for {@link EventHandler}s
     */
    private void scanListenerForEventHandlers(Object listener) {
        this.eventProfiler.preListenerDiscovery(listener);
        Set<HandlerEncapsulator<T>> persistentSet = new HashSet<>();
        Set<HandlerEncapsulator<T>> nonPersistentSet = new HashSet<>();

        this.persistentCache.put(listener, persistentSet);
        this.nonPersistentCache.put(listener, nonPersistentSet);

        int methodIndex = 0;
        for (Method method : listener.getClass().getDeclaredMethods()) {
            if ((method.getModifiers() & Modifier.PRIVATE) != 0) {
                continue;
            }
            if ((method.getParameterCount() == 1 || (method.getParameterCount() == 2 && EventTiming.class.isAssignableFrom(method.getParameterTypes()[1])))
                    && method.isAnnotationPresent(EventHandler.class) && this.BASE_CLASS.isAssignableFrom(method.getParameterTypes()[0])) {
                boolean includesTimingParam = method.getParameterCount() == 2;

                @SuppressWarnings("unchecked")
                Class<? extends T> eventClass = (Class<? extends T>) method.getParameterTypes()[0];
                EventHandler eventHandler = method.getAnnotation(EventHandler.class);

                HandlerEncapsulator<T> encapsulator;

                if (includesTimingParam) {
                    NavigableSet<HandlerEncapsulator<T>> preSet = this.getOrCreateNavigableSet(this.eventEncapsulatorMap.get(EventTiming.PRE), eventClass);
                    NavigableSet<HandlerEncapsulator<T>> postSet = this.getOrCreateNavigableSet(this.eventEncapsulatorMap.get(EventTiming.POST), eventClass);

                    encapsulator = new HandlerEncapsulatorWithTiming<>(listener, method, methodIndex, eventHandler.priority(), preSet, postSet);
                } else {
                    NavigableSet<HandlerEncapsulator<T>> navigableSet = this.getOrCreateNavigableSet(this.eventEncapsulatorMap.get(eventHandler.timing()), eventClass);

                    encapsulator = new HandlerEncapsulator<>(listener, method, methodIndex, eventHandler.priority(), navigableSet);
                }

                Set<HandlerEncapsulator<T>> encapsulatorSet = eventHandler.persistent() ? persistentSet : nonPersistentSet;
                encapsulatorSet.add(encapsulator);
            }
            methodIndex++;
        }
        this.eventProfiler.postListenerDiscovery(listener);
    }

    /**
     * Retrieves the {@link NavigableSet}&lt;{@link HandlerEncapsulator}&gt;
     * from the {@code encapsulatorMap} with the given {@code eventClass}.
     *
     * <p>
     * If this set is not found, a new {@link ConcurrentSkipListSet} is created and inserted into {@code encapsulatorMap}.
     * </p>
     *
     * @param encapsulatorMap the map that contains the {@link NavigableSet}s the or create the {@link NavigableSet} from
     * @param eventClass the {@link Class} to look up in the {@code encapsulatorMap}
     * @return the created or retrieved {@link NavigableSet} from the {@code encapsulatorMap}
     */
    protected NavigableSet<HandlerEncapsulator<T>> getOrCreateNavigableSet(Map<Class<? extends T>, NavigableSet<HandlerEncapsulator<T>>> encapsulatorMap, Class<? extends T> eventClass) {
        NavigableSet<HandlerEncapsulator<T>> navigableSet = encapsulatorMap.get(eventClass);

        if (navigableSet == null) {
            navigableSet = new ConcurrentSkipListSet<>(ENCAPSULATOR_SORTER);
            encapsulatorMap.put(eventClass, navigableSet);
        }

        return navigableSet;
    }

    /**
     * Deregisters all applicable {@link EventHandler}s contained in the {@code listener} instance with this {@link EventManager}.
     *
     * @param listener the instance of the {@code listener} to deregister
     * @param onlyRemovePersistent if this is TRUE, only {@link EventHandler#persistent()} are deregistered,
     *                             if this is FALSE, only non {@link EventHandler#persistent()} are deregistered
     * @throws ListenerNotAlreadyRegisteredException if this instance of a {@code listener} is not already registered
     * <br>
     * This could occur if the {@code listener} was already deregistered, or if it was never registered.
     */
    public void deregisterListener(Object listener, boolean onlyRemovePersistent) throws ListenerNotAlreadyRegisteredException {
        Map<Object, Boolean> listenerStates = onlyRemovePersistent ? this.listenerPersistentStates : this.listenerNonPersistentStates;
        Boolean state = listenerStates.get(listener);

        // check if state is equal to null or equal to FALSE
        if (state != Boolean.TRUE) {
            throw new ListenerNotAlreadyRegisteredException(listener);
        }

        listenerStates.put(listener, Boolean.FALSE);
        this.eventProfiler.onDeregisterListener(listener);
        Set<HandlerEncapsulator<T>> encapsulatorSet = onlyRemovePersistent ? this.persistentCache.get(listener) : this.nonPersistentCache.get(listener);
        for (HandlerEncapsulator<T> encapsulator : encapsulatorSet) {
            encapsulator.setEnabled(false);
        }
        this.registeredListenerCount -= encapsulatorSet.size();
    }

    /**
     * Deregisters all non {@link EventHandler#persistent()} {@link EventHandler}s contained in the {@code listener} instance with this {@link EventManager}.
     *
     * @see #deregisterListener(Object, boolean)
     *
     * @param listener the instance of the {@code listener} to deregister
     * @throws ListenerNotAlreadyRegisteredException if this instance of a {@code listener} is not already registered
     * <br>
     * This could occur if the {@code listener} was already deregistered, or if it was never registered.
     */
    public void deregisterListener(Object listener) throws ListenerNotAlreadyRegisteredException {
        this.deregisterListener(listener, false);
    }

    /**
     * This deregisters all registered listeners in this {@link EventManager}, disabling all {@link EventHandler}s.
     *
     * <p>
     * This also keeps all of the caches, so future registration of the same {@code listener}s are not expensive.
     * <br>
     * To explicitly clear all of the caches, consider {@link #cleanup()}.
     * </p>
     */
    public void deregisterAll() {
        this.eventEncapsulatorMap.get(EventTiming.PRE).values().forEach(Set::clear);
        this.eventEncapsulatorMap.get(EventTiming.POST).values().forEach(Set::clear);
        this.listenerPersistentStates.keySet().forEach(listener -> this.listenerPersistentStates.put(listener, Boolean.FALSE));
        this.listenerNonPersistentStates.keySet().forEach(listener -> this.listenerNonPersistentStates.put(listener, Boolean.FALSE));
        this.registeredListenerCount = 0;
        this.eventProfiler.onDeregisterAll();
    }

    /**
     * This cleans up this {@link EventManager}. This restores the {@link EventManager} to the default state.
     *
     * <p>
     * This invalidates all caches, disables all listeners, and calls {@link IEventProfiler#onCleanup()}.
     * <br>
     * This effectively creates a new {@link EventManager} instance, discarding the old one.
     * </p>
     */
    public void cleanup() {
        this.eventEncapsulatorMap.get(EventTiming.PRE).clear();
        this.eventEncapsulatorMap.get(EventTiming.POST).clear();

        this.listenerPersistentStates.clear();
        this.listenerNonPersistentStates.clear();
        this.persistentCache.clear();
        this.nonPersistentCache.clear();

        this.registeredListenerCount = 0;
        this.eventProfiler.onCleanup();
    }

    /**
     * Returns the number of unique {@link EventHandler}s that are registered to receive events.
     *
     * @return the number of {@link EventHandler}s that are currently activated
     */
    public int getRegisteredListenerCount() {
        return this.registeredListenerCount;
    }
    
    /**
     * Fires an {@code event} to all eligible {@link EventHandler}s.
     *
     * <p>
     * This causes all applicable {@link EventHandler}s to be invoked with the given {@code event} object.
     * <br>
     * An applicable {@link EventHandler} is a {@link Method} where the first parameter has the same type as {@code event},
     * with either: {@link EventHandler#timing()} equal to {@link EventTiming#PRE}, or a {@link Method} where the second
     * parameter has the {@link EventTiming} type.
     * </p>
     *
     * @see #fireEvent(Object, EventTiming) 
     * 
     * @param event the event instance to be propagated to all eligible {@link EventHandler}s, designated my this {@code event} type
     * @param <E> the type of the {@code event} fired, which must be a subclass of this {@link EventManager}'s {@link #BASE_CLASS}
     * @return the provided {@code event}
     */
    public <E extends T> E fireEvent(E event) {
        return this.fireEvent(event, EventTiming.PRE);
    }
    
    /**
     * Fires an {@code event} to all eligible {@link EventHandler}s.
     * 
     * <p>
     * This causes all applicable {@link EventHandler}s to be invoked with the given {@code event} object.
     * <br>
     * An applicable {@link EventHandler} is a {@link Method} where the first parameter has the same type as {@code event},
     * with either: {@link EventHandler#timing()} equal to {@code timing}, or a {@link Method} where the second
     * parameter has the {@link EventTiming} type.
     * </p>
     * 
     * @param event the event instance to be propagated to all eligible {@link EventHandler}s, designated my this {@code event} type and {@code timing}
     * @param timing the {@link EventTiming} of the {@code event}
     * @param <E> the type of the {@code event} fired, which must be a subclass of this {@link EventManager}'s {@link #BASE_CLASS}
     * @return the provided {@code event}
     */
    public synchronized <E extends T> E fireEvent(E event, EventTiming timing) {
        NavigableSet<HandlerEncapsulator<T>> encapsulatorSet = this.eventEncapsulatorMap.get(timing).get(event.getClass());

        if (encapsulatorSet == null || encapsulatorSet.isEmpty()) {
            this.eventProfiler.onSkippedEvent(event, timing);
        } else {
            this.eventProfiler.preFireEvent(event, timing, encapsulatorSet);

            for (HandlerEncapsulator<T> encapsulator : encapsulatorSet) {
                encapsulator.invoke(event, timing);
            }

            this.eventProfiler.postFireEvent(event, timing, encapsulatorSet);
        }

        return event;
    }

    /**
     * Set the current {@link IEventProfiler} for this {@link EventManager}.
     *
     * @param eventProfiler the new {@link IEventProfiler}
     */
    public void setEventProfiler(IEventProfiler<T> eventProfiler) {
        this.eventProfiler = eventProfiler;
    }
}
