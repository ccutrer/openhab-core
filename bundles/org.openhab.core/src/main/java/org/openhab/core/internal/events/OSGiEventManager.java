/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.internal.events;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import org.openhab.core.events.Event;
import org.openhab.core.events.EventFactory;
import org.openhab.core.events.EventSubscriber;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.event.EventHandler;

/**
 * The {@link OSGiEventManager} provides an OSGi based default implementation of the openHAB event bus.
 *
 * The OSGiEventHandler tracks {@link EventSubscriber}s and {@link EventFactory}s, receives OSGi events (by
 * implementing the OSGi {@link EventHandler} interface) and dispatches the received OSGi events as OH {@link Event}s
 * to the {@link EventSubscriber}s if the provided filter applies.
 *
 * @author Stefan Bußweiler - Initial contribution
 * @author Markus Rathgeb - Return on received events as fast as possible (handle event in another thread)
 * @author Cody Cutrer - Parse event.topics service property from EventSubscriber
 */
@Component(immediate = true, property = { "event.topics:String=openhab" })
public class OSGiEventManager implements EventHandler {

    /** The event subscribers indexed by the event type. */
    // Use a concurrent hash map because the map is written and read by different threads!
    private final Map<String, Set<EventSubscriber>> typedEventSubscribers = new ConcurrentHashMap<>();
    private final Map<String, EventFactory> typedEventFactories = new ConcurrentHashMap<>();
    private final Map<EventSubscriber, PathMatcher> eventSubscriberTopicFilters = new ConcurrentHashMap<>();

    private ThreadedEventHandler eventHandler;

    @Activate
    protected void activate(ComponentContext componentContext) {
        eventHandler = new ThreadedEventHandler(typedEventSubscribers, typedEventFactories,
                eventSubscriberTopicFilters);
        eventHandler.open();
    }

    @Deactivate
    protected void deactivate(ComponentContext componentContext) {
        if (eventHandler != null) {
            eventHandler.close();
            eventHandler = null;
        }
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    protected void addEventSubscriber(final EventSubscriber eventSubscriber, Map<String, Object> properties) {
        final Object topics = properties.get(EventSubscriber.EVENT_TOPICS_PROPERTY);
        if (topics instanceof String) {
            eventSubscriberTopicFilters.put(eventSubscriber, createTopicRegex((String) topics));
        }

        final Set<String> subscribedEventTypes = eventSubscriber.getSubscribedEventTypes();
        for (final String subscribedEventType : subscribedEventTypes) {
            final Set<EventSubscriber> entries = typedEventSubscribers.get(subscribedEventType);
            if (entries == null) {
                // Use a copy on write array set because the set is written and read by different threads!
                typedEventSubscribers.put(subscribedEventType, new CopyOnWriteArraySet<>(Set.of(eventSubscriber)));
            } else {
                entries.add(eventSubscriber);
            }
        }
    }

    protected void removeEventSubscriber(EventSubscriber eventSubscriber) {
        final Set<String> subscribedEventTypes = eventSubscriber.getSubscribedEventTypes();
        for (final String subscribedEventType : subscribedEventTypes) {
            final Set<EventSubscriber> entries = typedEventSubscribers.get(subscribedEventType);
            if (entries != null) {
                entries.remove(eventSubscriber);
                if (entries.isEmpty()) {
                    typedEventSubscribers.remove(subscribedEventType);
                }
            }
        }
        eventSubscriberTopicFilters.remove(eventSubscriber);
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    protected void addEventFactory(EventFactory eventFactory) {
        Set<String> supportedEventTypes = eventFactory.getSupportedEventTypes();

        for (String supportedEventType : supportedEventTypes) {
            synchronized (this) {
                if (!typedEventFactories.containsKey(supportedEventType)) {
                    typedEventFactories.put(supportedEventType, eventFactory);
                }
            }
        }
    }

    protected void removeEventFactory(EventFactory eventFactory) {
        Set<String> supportedEventTypes = eventFactory.getSupportedEventTypes();

        for (String supportedEventType : supportedEventTypes) {
            typedEventFactories.remove(supportedEventType);
        }
    }

    @Override
    public void handleEvent(org.osgi.service.event.Event osgiEvent) {
        eventHandler.handleEvent(osgiEvent);
    }

    private PathMatcher createTopicRegex(String topicStr) {
        return FileSystems.getDefault().getPathMatcher("glob:" + topicStr);
    }
}
