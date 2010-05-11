/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.msc.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import org.jboss.logging.Logger;
import org.jboss.msc.value.Value;

final class ServiceControllerImpl<S> implements ServiceController<S> {
    private static final Logger log = Logger.getI18nLogger("org.jboss.msc.controller", null, "MSC");

    private static final String ILLEGAL_CONTROLLER_STATE = "Illegal controller state";
    private static final String ILLEGAL_LOCK_STATE = "Illegal lock state";
    private static final String START_FAIL_EXCEPTION = "Start failed due to exception";
    private static final String SERVICE_REMOVED = "Service has been removed";
    private static final String SERVICE_IS_NOT_UP = "Service is not up";
    private static final String SERVICE_NOT_AVAILABLE = "Service is not available";

    /**
     * The service container which contains this instance.
     */
    private final ServiceContainerImpl container;
    /**
     * The service itself.
     */
    private final Value<? extends Service> serviceValue;
    /**
     * The value which is passed to listeners.
     */
    private final Value<S> value;
    /**
     * The source location in which this service was defined.
     */
    private final Location location;
    /**
     * The dependencies of this service.
     */
    private final ServiceControllerImpl<?>[] dependencies;
    /**
     * The set of registered service listeners.
     */
    private final Set<ServiceListener<? super S>> listeners = new HashSet<ServiceListener<? super S>>();
    /**
     * The start exception.
     */
    private StartException startException;
    /**
     * The controller mode.
     */
    private Mode mode = Mode.NEVER;
    /**
     * The controller state.
     */
    private Substate state = Substate.DOWN;
    /**
     * The number of dependents which place a demand-to-start on this dependency.  If this value is >0, propagate a demand
     * up to all parent dependents.  If this value is >0 and mode is ON_DEMAND, put a load of +1 on {@code upperCount}.
     */
    private int demandedByCount;
    /**
     * Semaphore count for bringing this dep up.  If the value is <= 0, the service is stopped.  Each unstarted
     * dependency will put a load of -1 on this value.  A mode of AUTOMATIC or IMMEDIATE will put a load of +1 on this
     * value.  A mode of NEVER will cause this value to be ignored.
     */
    private int upperCount;
    /**
     * The number of dependents that are currently running.  The deployment will not leave the {@link State#STOPPING} state
     * until all running dependents are stopped.
     */
    private int runningDependents;
    /**
     * The number of listeners that are currently running.
     */
    private int runningListeners;

    /**
     * Listener which is added to dependencies of this service.
     */
    private final ServiceListener<Object> dependencyListener = new ServiceListener<Object>() {
        public void serviceStarting(final ServiceController<? extends Object> serviceController) {
        }

        public void serviceStarted(final ServiceController<? extends Object> serviceController) {
            ServiceListener<? super S>[] listeners = null;
            synchronized (ServiceControllerImpl.this) {
                if (++upperCount == 1 && mode != Mode.NEVER) {
                    if (runningListeners == 0 && state == Substate.DOWN) {
                        listeners = getListeners(1, Substate.STARTING);
                    }
                }
            }
            if (listeners != null) doStart(listeners);
        }

        public void serviceFailed(final ServiceController<? extends Object> serviceController, final StartException reason) {
        }

        public void serviceStopping(final ServiceController<? extends Object> serviceController) {
            ServiceListener<? super S>[] listeners = null;
            synchronized (ServiceControllerImpl.this) {
                if (--upperCount == 0 || mode == Mode.NEVER) {
                    if (runningListeners == 0 && state == Substate.UP) {
                        listeners = getListeners(1, Substate.STOPPING);
                    }
                }
            }
            if (listeners != null) doStop(listeners);
        }

        public void serviceStopped(final ServiceController<? extends Object> serviceController) {
        }

        public void serviceRemoved(final ServiceController<? extends Object> serviceController) {
        }
    };

    ServiceControllerImpl(final ServiceContainerImpl container, final Value<? extends Service> serviceValue, final Value<S> value, final Location location, final ServiceControllerImpl<?>[] dependencies) {
        this.container = container;
        this.serviceValue = serviceValue;
        this.value = value;
        this.location = location;
        this.dependencies = dependencies;
        upperCount = - dependencies.length;
        for (ServiceControllerImpl<?> controller : dependencies) {
            controller.addListener(dependencyListener);
        }
    }

    public ServiceContainer getServiceContainer() {
        return container;
    }

    public State getState() {
        return state.getState();
    }

    public S getValue() throws IllegalStateException {
        return value.getValue();
    }

    public void addListener(final ServiceListener<? super S> listener) {
        assert ! Thread.holdsLock(this);
        final Substate state;
        synchronized (this) {
            runningListeners ++;
            state = this.state;
            if (state != Substate.REMOVED) listeners.add(listener);
        }
        invokeListener(listener, state.getState());
    }

    private void invokeListener(final ServiceListener<? super S> listener, final State state) {
        assert ! Thread.holdsLock(this);
        try {
            switch (state) {
                case DOWN: {
                    listener.serviceStopped(this);
                    break;
                }
                case STARTING: {
                    listener.serviceStarting(this);
                    break;
                }
                case START_FAILED: {
                    listener.serviceFailed(this, startException);
                    break;
                }
                case UP: {
                    listener.serviceStarted(this);
                    break;
                }
                case STOPPING: {
                    listener.serviceStopping(this);
                    break;
                }
                case REMOVED: {
                    listener.serviceRemoved(this);
                    break;
                }
            }
        } finally {
            doFinishListener(null);
        }
    }

    public void removeListener(final ServiceListener<? super S> listener) {
        synchronized (this) {
            listeners.remove(listener);
        }
    }

    public void remove() throws IllegalStateException {
        final ServiceListener<? super S>[] listeners;
        synchronized (this) {
            if (state == Substate.DOWN) {
                state = Substate.REMOVED;
                final ServiceListener<Object> dependencyListener = this.dependencyListener;
                for (ServiceControllerImpl<?> controller : dependencies) {
                    controller.removeListener(dependencyListener);
                }
                listeners = getListeners(0, Substate.REMOVED);
                this.listeners.clear();
                Arrays.fill(dependencies, null);
            } else {
                throw new IllegalStateException(ILLEGAL_CONTROLLER_STATE);
            }
        }
        runListeners(listeners, State.REMOVED);
    }

    public StartException getStartException() {
        synchronized (this) {
            return startException;
        }
    }

    public void retry() {
        assert ! Thread.holdsLock(this);
        final ServiceListener<? super S>[] listeners;
        synchronized (this) {
            if (state.getState() != State.START_FAILED) {
                return;
            }
            if (upperCount == 1) {
                assert mode != Mode.NEVER;
                listeners = getListeners(1, Substate.STARTING);
            } else {
                state = Substate.START_FAILED_RETRY_PENDING;
                return;
            }
        }
        doStart(listeners);
    }

    public Mode getMode() {
        synchronized (this) {
            return mode;
        }
    }

    public void setMode(final Mode newMode) {
        assert ! Thread.holdsLock(this);
        Substate newState = null;
        ServiceListener<? super S>[] listeners = null;
        synchronized (this) {
            final Substate state = this.state;
            if (state == Substate.REMOVED) {
                throw new IllegalStateException(SERVICE_REMOVED);
            }
            final Mode oldMode = mode;
            mode = newMode;
            switch (oldMode) {
                case NEVER: {
                    switch (newMode) {
                        case NEVER: {
                            return;
                        }
                        case ON_DEMAND: {
                            if (demandedByCount > 0) {
                                upperCount++;
                            }
                            if (state == Substate.DOWN && upperCount > 0) {
                                listeners = getListeners(1, newState = Substate.STARTING);
                            }
                            break;
                        }
                        case AUTOMATIC: {
                            if (upperCount++ == 0 && state == Substate.DOWN) {
                                listeners = getListeners(1, newState = Substate.STARTING);
                            }
                            break;
                        }
                        case IMMEDIATE: {
                            doDemandParents();
                            if (upperCount++ == 0 && state == Substate.DOWN) {
                                listeners = getListeners(1, newState = Substate.STARTING);
                            }
                            break;
                        }
                    }
                    break;
                }
                case ON_DEMAND: {
                    switch (newMode) {
                        case NEVER: {
                            if (demandedByCount > 0) {
                                upperCount--;
                            }
                            if (state == Substate.UP) {
                                listeners = getListeners(1, newState = Substate.STOPPING);
                            } else if (state.getState() == State.START_FAILED) {
                                listeners = getListeners(0, newState = Substate.DOWN);
                            }
                            break;
                        }
                        case ON_DEMAND: {
                            return;
                        }
                        case AUTOMATIC: {
                            if (demandedByCount == 0) {
                                upperCount++;
                            }
                            if (upperCount > 0 && state == Substate.DOWN) {
                                listeners = getListeners(1, newState = Substate.STARTING);
                            }
                            break;
                        }
                        case IMMEDIATE: {
                            doDemandParents();
                            if (demandedByCount == 0) {
                                upperCount++;
                            }
                            if (upperCount > 0 && state == Substate.DOWN) {
                                listeners = getListeners(1, newState = Substate.STARTING);
                            }
                            break;
                        }
                    }
                    break;
                }
                case AUTOMATIC: {
                    switch (newMode) {
                        case NEVER: {
                            upperCount--;
                            if (state == Substate.UP) {
                                listeners = getListeners(1, newState = Substate.STOPPING);
                            }
                            break;
                        }
                        case ON_DEMAND: {
                            if (demandedByCount == 0) {
                                upperCount--;
                            }
                            if (state == Substate.UP && upperCount == 0) {
                                listeners = getListeners(1, newState = Substate.STOPPING);
                            }
                            break;
                        }
                        case AUTOMATIC: {
                            return;
                        }
                        case IMMEDIATE: {
                            doDemandParents();
                            break;
                        }
                    }
                    break;
                }
                case IMMEDIATE: {
                    switch (newMode) {
                        case NEVER: {
                            upperCount--;
                            if (state == Substate.UP) {
                                listeners = getListeners(1, newState = Substate.STOPPING);
                            }
                            break;
                        }
                        case ON_DEMAND: {
                            if (demandedByCount == 0) {
                                upperCount--;
                            }
                            if (state == Substate.UP && upperCount == 0) {
                                listeners = getListeners(1, newState = Substate.STOPPING);
                            }
                            break;
                        }
                        case AUTOMATIC: {
                            doUndemandParents();
                            break;
                        }
                        case IMMEDIATE: {
                            return;
                        }
                    }
                    break;
                }
            }
        }
        if (newState != null) switch (newState) {
            case STARTING: {
                doStart(listeners);
                break;
            }
            case STOPPING: {
                doStop(listeners);
                break;
            }
            case DOWN: {
                doStopComplete(listeners);
                break;
            }
        }
    }

    enum ContextState {
        SYNC,
        ASYNC,
        COMPLETE,
        FAILED,
    }

    /**
     * Set a new state and get the state listeners under the lock.
     *
     * @param plusCount the number of extra listeners to register, if any
     * @param newState the new state to set
     * @return the listeners
     */
    @SuppressWarnings({ "unchecked" })
    ServiceListener<? super S>[] getListeners(int plusCount, Substate newState) {
        assert Thread.holdsLock(this);
        state = newState;
        if (newState == Substate.STARTING || newState == Substate.DOWN) {
            // special case - clear out the old exception
            startException = null;
        }
        final Set<ServiceListener<? super S>> listeners = this.listeners;
        final int size = listeners.size();
        runningListeners = size + plusCount;
        return listeners.toArray(new ServiceListener[size]);
    }

    /**
     * Run the service listeners outside of the lock.
     *
     * @param listeners the listeners
     * @param state the state we are in
     */
    void runListeners(final ServiceListener<? super S>[] listeners, final State state) {
        assert ! Thread.holdsLock(this);
        final Executor executor = container.getExecutor();
        for (final ServiceListener<? super S> listener : listeners) {
            try {
                executor.execute(new Runnable() {
                    public void run() {
                        assert ! Thread.holdsLock(ServiceControllerImpl.this);
                        invokeListener(listener, state);
                    }
                });
            } catch (RuntimeException e) {
                // todo log it and continue
            }
        }
    }

    private void doStart(ServiceListener<? super S>[] listeners) {
        assert ! Thread.holdsLock(this);
        assert state == Substate.DOWN || state == Substate.START_FAILED_RETRY_PENDING;
        try {
            final Service service = serviceValue.getValue();
            if (service == null) {
                throw new IllegalStateException(SERVICE_NOT_AVAILABLE);
            }
            runListeners(listeners, State.STARTING);
            final Executor executor = container.getExecutor();
            executor.execute(new Runnable() {
                public void run() {
                    assert ! Thread.holdsLock(ServiceControllerImpl.this);
                    final StartContextImpl context = new StartContextImpl();
                    try {
                        service.start(context);
                        synchronized (ServiceControllerImpl.this) {
                            if (context.state == ContextState.SYNC) {
                                context.state = ContextState.COMPLETE;
                                doFinishListener(null);
                            }
                        }
                    } catch (StartException e) {
                        synchronized (ServiceControllerImpl.this) {
                            final ContextState oldState = context.state;
                            if (oldState == ContextState.SYNC || oldState == ContextState.ASYNC) {
                                context.state = ContextState.FAILED;
                                doFinishListener(e);
                            } else {
                                // todo log warning
                            }
                        }
                    } catch (Throwable t) {
                        synchronized (ServiceControllerImpl.this) {
                            final ContextState oldState = context.state;
                            if (oldState == ContextState.SYNC || oldState == ContextState.ASYNC) {
                                context.state = ContextState.FAILED;
                                startException = new StartException("Failed to start service", t, location);
                                doFinishListener(null);
                            } else {
                                // todo log warning
                            }
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            doFail(new StartException(START_FAIL_EXCEPTION, e));
        }
    }

    private void doStartComplete(final ServiceListener<? super S>[] listeners) {
        runListeners(listeners, State.UP);
    }

    private void doFinishListener(StartException e) {
        assert ! Thread.holdsLock(this);
        Substate newState = null;
        ServiceListener<? super S>[] listeners = null;
        synchronized (this) {
            if (e != null) startException = e;
            if (--runningListeners == 0) {
                switch (state) {
                    case DOWN: {
                        if (upperCount > 0 && mode != Mode.NEVER) {
                            listeners = getListeners(1, newState = Substate.STARTING);
                        }
                        break;
                    }
                    case STARTING: {
                        if (startException != null) {
                            listeners = getListeners(0, newState = Substate.START_FAILED);
                        } else {
                            listeners = getListeners(0, newState = Substate.UP);
                        }
                        break;
                    }
                    case START_FAILED: {
                        if (upperCount <= 0 || mode == Mode.NEVER) {
                            listeners = getListeners(0, newState = Substate.DOWN);
                        } else if (state == Substate.START_FAILED_RETRY_PENDING) {
                            listeners = getListeners(1, newState = Substate.STARTING);
                        }
                        break;
                    }
                    case UP: {
                        if (upperCount <= 0 || mode == Mode.NEVER) {
                            listeners = getListeners(1, newState = Substate.STOPPING);
                        }
                        break;
                    }
                    case STOPPING: {
                        listeners = getListeners(0, newState = Substate.DOWN);
                        break;
                    }
                    case REMOVED: {
                        // nothing to do; the service is utterly done
                        break;
                    }
                }
            }
        }
        if (newState != null) switch (newState) {
            case STARTING: {
                doStart(listeners);
                break;
            }
            case UP: {
                doStartComplete(listeners);
                break;
            }
            case STOPPING: {
                doStop(listeners);
                break;
            }
            case DOWN: {
                doStopComplete(listeners);
                break;
            }
        }
    }

    private void doFail(final StartException e) {
        assert Thread.holdsLock(this);
        assert state == Substate.STARTING;
        state = Substate.START_FAILED;
        startException = e;
        // todo invoke listeners
    }

    private void doStop(final ServiceListener<? super S>[] listeners) {
        assert Thread.holdsLock(this);
        assert state == Substate.UP;
        try {
            final Service service = serviceValue.getValue();
            if (service == null) {
                throw new IllegalStateException(SERVICE_NOT_AVAILABLE);
            }
            runListeners(listeners, State.STOPPING);
            final Executor executor = container.getExecutor();
            executor.execute(new Runnable() {
                public void run() {
                    assert ! Thread.holdsLock(ServiceControllerImpl.this);
                    final StopContextImpl context = new StopContextImpl();
                    try {
                        service.stop(context);
                        synchronized (ServiceControllerImpl.this) {
                            if (context.state == ContextState.SYNC) {
                                context.state = ContextState.COMPLETE;
                                doFinishListener(null);
                            }
                        }
                    } catch (Throwable t) {
                        synchronized (ServiceControllerImpl.this) {
                            final ContextState oldState = context.state;
                            if (oldState == ContextState.SYNC || oldState == ContextState.ASYNC) {
                                context.state = ContextState.FAILED;
                                doFinishListener(null);
                            } else {
                            }
                        }
                        // todo log warning
                    }
                }
            });
        } catch (RuntimeException e) {
            doFail(new StartException(START_FAIL_EXCEPTION, e));
        }
    }

    private void doStopComplete(final ServiceListener<? super S>[] listeners) {
        runListeners(listeners, State.DOWN);
    }

    private void doDemandParents() {
        assert Thread.holdsLock(this);
        for (ServiceControllerImpl<?> dependency : dependencies) {
            dependency.addDemand();
        }
    }

    private void doUndemandParents() {
        assert Thread.holdsLock(this);
        for (ServiceControllerImpl<?> dependency : dependencies) {
            dependency.removeDemand();
        }
    }

    void addDemand() {
        final ServiceListener<? super S>[] listeners;
        synchronized (this) {
            final int cnt = demandedByCount++;
            if (cnt != 0 || mode != Mode.ON_DEMAND) {
                return;
            }
            if (upperCount++ != 0 || state != Substate.DOWN || runningListeners != 0) {
                return;
            }
            listeners = getListeners(1, Substate.STARTING);
        }
        if (listeners != null) doStart(listeners);
    }

    void removeDemand() {
        final boolean stop;
        final ServiceListener<? super S>[] listeners;
        synchronized (this) {
            final int cnt = --demandedByCount;
            if (cnt != 0 || mode != Mode.ON_DEMAND) {
                return;
            }
            if (--upperCount != 0 || runningListeners != 0) {
                return;
            }
            if (state == Substate.UP) {
                stop = true;
                listeners = getListeners(1, Substate.STOPPING);
            } else if (state.getState() == State.START_FAILED) {
                stop = false;
                listeners = getListeners(0, Substate.DOWN);
            } else {
                return;
            }
        }
        if (stop) {
            doStop(listeners);
        } else {
            doStopComplete(listeners);
        }
    }

    void dependentStarted() {
        synchronized (this) {
            runningDependents++;
        }
    }

    void dependentStopped() {
        final ServiceListener<? super S>[] listeners;
        synchronized (this) {
            if (--runningDependents != 0) {
                return;
            }
            if (state != Substate.STOPPING || runningListeners != 0) {
                return;
            }
            listeners = getListeners(0, Substate.DOWN);
        }
        doStopComplete(listeners);
    }

    private class StartContextImpl implements StartContext {

        private ContextState state = ContextState.SYNC;

        public void failed(final StartException reason) throws IllegalStateException {
            synchronized (ServiceControllerImpl.this) {
                if (state == ContextState.ASYNC) {
                    state = ContextState.FAILED;
                    doFinishListener(reason);
                } else {
                    throw new IllegalStateException(ILLEGAL_CONTROLLER_STATE);
                }
            }
        }

        public void asynchronous() throws IllegalStateException {
            synchronized (ServiceControllerImpl.this) {
                if (state == ContextState.SYNC) {
                    state = ContextState.ASYNC;
                } else {
                    throw new IllegalStateException(ILLEGAL_CONTROLLER_STATE);
                }
            }
        }

        public void complete() throws IllegalStateException {
            synchronized (ServiceControllerImpl.this) {
                if (state == ContextState.ASYNC) {
                    state = ContextState.COMPLETE;
                    doFinishListener(null);
                } else {
                    throw new IllegalStateException(ILLEGAL_CONTROLLER_STATE);
                }
            }
        }
    }

    private class StopContextImpl implements StopContext {

        private ContextState state = ContextState.SYNC;

        public void asynchronous() throws IllegalStateException {
            synchronized (ServiceControllerImpl.this) {
                if (state == ContextState.SYNC) {
                    state = ContextState.ASYNC;
                } else {
                    throw new IllegalStateException(ILLEGAL_CONTROLLER_STATE);
                }
            }
        }

        public void complete() throws IllegalStateException {
            synchronized (ServiceControllerImpl.this) {
                if (state == ContextState.ASYNC) {
                    state = ContextState.COMPLETE;
                    doFinishListener(null);
                } else {
                    throw new IllegalStateException(ILLEGAL_CONTROLLER_STATE);
                }
            }
        }
    }

    enum Substate {
        DOWN(State.DOWN),
        STARTING(State.STARTING),
        START_FAILED(State.START_FAILED),
        START_FAILED_RETRY_PENDING(State.START_FAILED),
        UP(State.UP),
        STOPPING(State.STOPPING),
        REMOVED(State.REMOVED),
        ;
        private final State state;

        Substate(final State state) {
            this.state = state;
        }

        public State getState() {
            return state;
        }
    }
}
