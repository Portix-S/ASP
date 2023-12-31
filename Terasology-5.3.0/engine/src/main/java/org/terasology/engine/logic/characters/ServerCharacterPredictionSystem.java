// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.logic.characters;


import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.core.Time;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.entity.lifecycleEvents.BeforeDeactivateComponent;
import org.terasology.engine.entitySystem.entity.lifecycleEvents.OnActivatedComponent;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.engine.logic.characters.events.SetMovementModeEvent;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.logic.players.LocalPlayer;
import org.terasology.engine.network.NetworkSystem;
import org.terasology.engine.physics.engine.CharacterCollider;
import org.terasology.engine.physics.engine.PhysicsEngine;
import org.terasology.engine.recording.CharacterStateEventPositionMap;
import org.terasology.engine.recording.RecordAndReplayCurrentStatus;
import org.terasology.engine.recording.RecordAndReplayStatus;
import org.terasology.engine.registry.In;
import org.terasology.engine.registry.Share;
import org.terasology.engine.utilities.collection.CircularBuffer;
import org.terasology.engine.world.WorldProvider;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;

import java.util.List;
import java.util.Map;

@RegisterSystem(RegisterMode.AUTHORITY)
@Share(PredictionSystem.class)
public class ServerCharacterPredictionSystem extends BaseComponentSystem implements UpdateSubscriberSystem,
        PredictionSystem {
    public static final int RENDER_DELAY = 100;
    public static final int MAX_INPUT_OVERFLOW = 100;
    public static final int MAX_INPUT_UNDERFLOW = 100;
    private static final int MAX_INPUT_OVERFLOW_REPLAY_INCREASE = 120;

    private static final Logger logger = LoggerFactory.getLogger(ServerCharacterPredictionSystem.class);

    private static final int BUFFER_SIZE = 128;
    private static final int TIME_BETWEEN_STATE_REPLICATE = 50;

    @In
    private Time time;

    @In
    private PhysicsEngine physics;

    @In
    private WorldProvider worldProvider;

    @In
    private LocalPlayer localPlayer;

    @In
    private NetworkSystem networkSystem;

    @In
    private CharacterStateEventPositionMap characterStateEventPositionMap;

    @In
    private RecordAndReplayCurrentStatus recordAndReplayCurrentStatus;

    private CharacterMover characterMover;
    private Map<EntityRef, CircularBuffer<CharacterStateEvent>> characterStates = Maps.newHashMap();
    private List<EntityRef> characterStatesToRemove = Lists.newArrayList();
    private Map<EntityRef, CharacterMoveInputEvent> lastInputEvent = Maps.newHashMap();
    private long nextSendState;
    private CharacterMovementSystemUtility characterMovementSystemUtility;

    @Override
    public void initialise() {
        characterMover = new KinematicCharacterMover(worldProvider, physics);
        nextSendState = time.getGameTimeInMs() + TIME_BETWEEN_STATE_REPLICATE;
        characterMovementSystemUtility = new CharacterMovementSystemUtility(physics);
    }

    @ReceiveEvent(components = {CharacterMovementComponent.class, LocationComponent.class,
            AliveCharacterComponent.class})
    public void onCreate(final OnActivatedComponent event, final EntityRef entity) {
        physics.getCharacterCollider(entity);
        CircularBuffer<CharacterStateEvent> stateBuffer = CircularBuffer.create(BUFFER_SIZE);
        stateBuffer.add(createInitialState(entity));
        characterStates.put(entity, stateBuffer);
    }

    @ReceiveEvent(components = {CharacterMovementComponent.class, LocationComponent.class,
            AliveCharacterComponent.class})
    public void onDestroy(final BeforeDeactivateComponent event, final EntityRef entity) {
        physics.removeCharacterCollider(entity);
        characterStatesToRemove.add(entity);
        lastInputEvent.remove(entity);
    }

    @ReceiveEvent(components = AliveCharacterComponent.class)
    public void onSetMovementModeEvent(SetMovementModeEvent event, EntityRef character,
                                       CharacterMovementComponent movementComponent) {
        CircularBuffer<CharacterStateEvent> stateBuffer = characterStates.get(character);
        CharacterStateEvent lastState = stateBuffer.getLast();
        CharacterStateEvent newState = new CharacterStateEvent(lastState);
        newState.setSequenceNumber(lastState.getSequenceNumber());
        if (event.getMode() != lastState.getMode()) {
            newState.setMode(event.getMode());
        } else {
            newState.setMode(MovementMode.WALKING);
        }
        stateBuffer.add(newState);
        characterMovementSystemUtility.setToState(character, newState);
    }

    @ReceiveEvent(components = {CharacterMovementComponent.class, LocationComponent.class,
            AliveCharacterComponent.class})
    public void onPlayerInput(CharacterMoveInputEvent input, EntityRef entity) {
        CharacterCollider characterCollider = physics.getCharacterCollider(entity);
        if (characterCollider.isPending()) {
            logger.debug("Skipping input, collision not yet established");
            return;
        }
        CircularBuffer<CharacterStateEvent> stateBuffer = characterStates.get(entity);
        CharacterStateEvent lastState = stateBuffer.getLast();
        float delta = input.getDeltaMs() + lastState.getTime() - (time.getGameTimeInMs() + MAX_INPUT_OVERFLOW);
        if (recordAndReplayCurrentStatus.getStatus() == RecordAndReplayStatus.REPLAYING) {
            delta -= MAX_INPUT_OVERFLOW_REPLAY_INCREASE;
        }
        if (delta < 0) {
            CharacterStateEvent newState = stepState(input, lastState, entity);
            stateBuffer.add(newState);

            if (recordAndReplayCurrentStatus.getStatus() == RecordAndReplayStatus.REPLAYING) {
                characterStateEventPositionMap.updateCharacterStateEvent(newState);
            } else if (recordAndReplayCurrentStatus.getStatus() == RecordAndReplayStatus.RECORDING) {
                characterStateEventPositionMap.add(newState.getSequenceNumber(), newState.getPosition(),
                        newState.getVelocity());
            }

            characterMovementSystemUtility.setToState(entity, newState);
            lastInputEvent.put(entity, input);
        } else {
            logger.warn("Received too much input from {}, dropping input. Delta difference: {}", entity, delta);
        }
    }

    @ReceiveEvent(components = {CharacterMovementComponent.class, LocationComponent.class,
            AliveCharacterComponent.class})
    public void onTeleport(CharacterTeleportEvent event, EntityRef entity) {
        CircularBuffer<CharacterStateEvent> stateBuffer = characterStates.get(entity);
        CharacterStateEvent lastState = stateBuffer.getLast();
        CharacterStateEvent newState = new CharacterStateEvent(lastState);
        newState.setPosition(new Vector3f(event.getTargetPosition()));
        newState.setTime(time.getGameTimeInMs());
        stateBuffer.add(newState);
        characterMovementSystemUtility.setToState(entity, newState);

    }

    @ReceiveEvent(components = {CharacterMovementComponent.class, LocationComponent.class,
            AliveCharacterComponent.class})
    public void onImpulse(CharacterImpulseEvent event, EntityRef entity) {
        Vector3f impulse = event.getDirection();

        CircularBuffer<CharacterStateEvent> stateBuffer = characterStates.get(entity);
        CharacterStateEvent lastState = stateBuffer.getLast();
        CharacterStateEvent newState = new CharacterStateEvent(lastState);
        newState.setVelocity(impulse.add(newState.getVelocity()));
        newState.setTime(time.getGameTimeInMs());
        newState.setGrounded(false);
        stateBuffer.add(newState);
        characterMovementSystemUtility.setToState(entity, newState);
    }

    private CharacterStateEvent createInitialState(EntityRef entity) {
        LocationComponent location = entity.getComponent(LocationComponent.class);
        return new CharacterStateEvent(time.getGameTimeInMs(), 0, location.getWorldPosition(new Vector3f()),
                location.getWorldRotation(new Quaternionf()), new Vector3f(), 0, 0, MovementMode.WALKING, false);
    }

    private CharacterStateEvent stepState(CharacterMoveInputEvent input, CharacterStateEvent lastState,
                                          EntityRef entity) {
        return characterMover.step(lastState, input, entity);
    }

    @Override
    public void update(float delta) {
        if (nextSendState < time.getGameTimeInMs()) {
            long lastSendTime = nextSendState - TIME_BETWEEN_STATE_REPLICATE;
            for (Map.Entry<EntityRef, CircularBuffer<CharacterStateEvent>> entry : characterStates.entrySet()) {
                if (entry.getValue().size() > 0) {
                    CharacterStateEvent state = entry.getValue().getLast();
                    if (state.getTime() >= lastSendTime) {
                        entry.getKey().send(state);
                    } else if (time.getGameTimeInMs() - state.getTime() > MAX_INPUT_UNDERFLOW) {
                        // Haven't received input in a while, repeat last input
                        CharacterMoveInputEvent lastInput = lastInputEvent.get(entry.getKey());
                        if (lastInput != null) {
                            CharacterMoveInputEvent newInput = new CharacterMoveInputEvent(lastInput,
                                    (int) (time.getGameTimeInMs() - state.getTime()));
                            onPlayerInput(newInput, entry.getKey());
                        }
                        entry.getKey().send(state);
                    }
                }
            }
            nextSendState += TIME_BETWEEN_STATE_REPLICATE;
        }
        long renderTime = time.getGameTimeInMs() - RENDER_DELAY;
        for (Map.Entry<EntityRef, CircularBuffer<CharacterStateEvent>> entry : characterStates.entrySet()) {
            if (entry.getKey().equals(localPlayer.getCharacterEntity())) {
                continue;
            }
            if (!characterStatesToRemove.contains(entry.getKey())) {
                setToTime(renderTime, entry.getKey(), entry.getValue());
            }
        }
        characterStates.keySet().removeAll(characterStatesToRemove);
        characterStatesToRemove.clear();
    }

    private void setToTime(long renderTime, EntityRef entity, CircularBuffer<CharacterStateEvent> buffer) {
        CharacterStateEvent previous = null;
        CharacterStateEvent next = null;
        for (CharacterStateEvent state : buffer) {
            if (state.getTime() <= renderTime) {
                previous = state;
            } else {
                next = state;
                break;
            }
        }
        if (previous != null) {
            if (next != null) {
                characterMovementSystemUtility.setToInterpolateState(entity, previous, next, renderTime);
            } else {
                characterMovementSystemUtility.setToExtrapolateState(entity, previous, renderTime);
            }
        }
    }

    @Override
    public void lagCompensate(EntityRef client, long timeMs) {
        for (Map.Entry<EntityRef, CircularBuffer<CharacterStateEvent>> entry : characterStates.entrySet()) {
            if (networkSystem.getOwnerEntity(entry.getKey()).equals(client)) {
                characterMovementSystemUtility.setToState(entry.getKey(), entry.getValue().getLast());
            } else {
                setToTime(timeMs - RENDER_DELAY, entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public void restoreToPresent() {
        long renderTime = time.getGameTimeInMs() - RENDER_DELAY;
        for (Map.Entry<EntityRef, CircularBuffer<CharacterStateEvent>> entry : characterStates.entrySet()) {
            setToTime(renderTime, entry.getKey(), entry.getValue());
        }
    }
}
