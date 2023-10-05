const initialStates = ['INITIAL_IPV_JOURNEY'];
const errorStates = ['ERROR'];
const failureStates = ['PYI_KBV_FAIL', 'PYI_NO_MATCH', 'PYI_ANOTHER_WAY'];

// Traverse the journey map to collect the available 'disabled' and 'featureFlag' options
export const getOptions = (journeyMap) => {
    const disabledOptions = [];
    const featureFlagOptions = [];

    Object.values(journeyMap).forEach((definition) => {
        const events = definition.events || definition.exitEvents || {};
        Object.values(events).forEach((def) => {
            recurseCheckIfDisabledOptions(def, disabledOptions);
            recurseCheckFeatureFlagOptions(def, featureFlagOptions);
        });
    });

    return { disabledOptions, featureFlagOptions };
};

// Make sure we've collected options only defined on events within a `checkIfDisabled` block.
const recurseCheckIfDisabledOptions = (def, disabledOptions) => {
    if (def.checkIfDisabled) {
        Object.keys(def.checkIfDisabled).forEach((opt) => {
            if (!disabledOptions.includes(opt)) {
                disabledOptions.push(opt);
            }
            recurseCheckIfDisabledOptions(def.checkIfDisabled[opt], disabledOptions)
        });
    }
};

// Make sure we've collected options only defined on events within a `checkFeatureFlag` block.
const recurseCheckFeatureFlagOptions = (def, featureFlagOptions) => {
    if (def.checkFeatureFlag) {
        Object.keys(def.checkFeatureFlag).forEach((opt) => {
            if (!featureFlagOptions.includes(opt)) {
                featureFlagOptions.push(opt);
            }
            recurseCheckFeatureFlagOptions(def.checkFeatureFlag[opt], featureFlagOptions)
        });
    }
};

// Expand out parent states
const expandParents = (journeyMap) => {
    const parentStates = [];
    Object.entries(journeyMap).forEach(([state, definition]) => {
        if (definition.parent) {
            const parent = journeyMap[definition.parent];
            definition.events = {
                ...parent.events,
                ...definition.events,
            };
            journeyMap[state] = { ...parent, ...definition };
            parentStates.push(definition.parent);
        }
    });
    parentStates.forEach((state) => delete journeyMap[state]);
};

// Expand out nested states
const expandNestedJourneys = (journeyMap, subjourneys) => {
    Object.entries(journeyMap).forEach(([state, definition]) => {
        if (definition.nestedJourney && subjourneys[definition.nestedJourney]) {
            delete journeyMap[state];
            const subjourney = subjourneys[definition.nestedJourney];

            // Expand out each of the nested states
            Object.entries(subjourney.nestedJourneyStates).forEach(([nestedState, nestedDefinition]) => {
                // Copy to avoid mutating different versions of the expanded definition
                const expandedDefinition = JSON.parse(JSON.stringify(nestedDefinition));

                Object.entries(expandedDefinition.events).forEach(([evt, def]) => {
                    // Map target states to expanded states
                    if (def.targetState) {
                        def.targetState = `${def.targetState}_${state}`;
                    }

                    // Map exit events to targets in the parent definition
                    if (def.exitEventToEmit) {
                        if (definition.exitEvents[def.exitEventToEmit]) {
                        def.targetState = definition.exitEvents[def.exitEventToEmit].targetState;
                        } else {
                            console.warn(`Unhandled exit event from ${state}:`, def.exitEventToEmit)
                            delete expandedDefinition.events[evt];
                        }
                        delete def.exitEventToEmit;
                    }
                });

                journeyMap[`${nestedState}_${state}`] = expandedDefinition;
            });

            // Update entry events on other states to expanded states
            Object.entries(subjourney.entryEvents).forEach(([entryEvent, def]) => {
                Object.values(journeyMap).forEach((journeyDef) => {
                    if (journeyDef.events?.[entryEvent]?.targetState === state) {
                        journeyDef.events[entryEvent].targetState = `${def.targetState}_${state}`;
                    }
                });
            });
        }
    });
};

// Should match logic in BasicEvent.java
const resolveEventTarget = (definition, formData) => {
    const disabledCriTargetState = recurseToCheckIfDisabledTargetState(definition.checkIfDisabled || {}, formData, null);
    if (disabledCriTargetState) {
        return disabledCriTargetState;
    }
    const checkFeatureFlagTargetState = recurseToCheckFeatureFlagTargetState(definition.checkFeatureFlag || {}, formData, null);
    if (checkFeatureFlagTargetState) {
        return checkFeatureFlagTargetState;
    }
    return definition.targetState;
};

// Find the target state for the first disabled CRI defined in a `checkIfDisabled` block, and also check if it's event
// has a `checkIfDisabled` block. If it does return that events target state. All the way down.
const recurseToCheckIfDisabledTargetState = (checkIfDisabledObject, formData, disabledCriTargetState) => {
    Object.keys(checkIfDisabledObject).forEach((cri) => {
        if (formData.getAll('disabledCri').includes(cri)) {
            disabledCriTargetState = checkIfDisabledObject[cri].targetState;
            if (checkIfDisabledObject[cri].checkIfDisabled) {
                disabledCriTargetState = recurseToCheckIfDisabledTargetState(checkIfDisabledObject[cri].checkIfDisabled, formData, disabledCriTargetState)
            }
        }
    })
    return disabledCriTargetState;
};

// Find the target state for the first checked feture flag defined in a `checkFeatureFlag` block, and also check if it's
// event has a `checkFeatureFlag` block. If it does return that events target state. All the way down.
const recurseToCheckFeatureFlagTargetState = (checkFeatureFlagObject, formData, featureFlagTargetState) => {
    Object.keys(checkFeatureFlagObject).forEach((flag) => {
        if (formData.getAll('flag').includes(flag)) {
            featureFlagTargetState = checkFeatureFlagObject[flag].targetState;
            if (checkFeatureFlagObject[flag].checkFeatureFlag) {
                featureFlagTargetState = recurseToCheckFeatureFlagTargetState(checkFeatureFlagObject[flag].checkFeatureFlag, formData, featureFlagTargetState)
            }
        }
    })
    return featureFlagTargetState;
};


// Render the transitions into mermaid, while tracking the states traced from the initial states
// This allows us to skip
const renderTransitions = (journeyMap, formData) => {
    const states = [...initialStates];
    const stateTransitions = [];

    for (let i = 0; i < states.length; i++) {
        const state = states[i];
        const definition = journeyMap[state];
        const events = definition.events || definition.exitEvents || {};

        const eventsByTarget = {};
        Object.entries(events).forEach(([eventName, def]) => {
            const target = resolveEventTarget(def, formData);

            if (errorStates.includes(target) && !formData.has('includeErrors')) {
                return;
            }
            if (failureStates.includes(target) && !formData.has('includeFailures')) {
                return;
            }

            if (!states.includes(target)) {
                states.push(target);
            }
            eventsByTarget[target] = eventsByTarget[target] || [];
            eventsByTarget[target].push(eventName);
        });

        Object.entries(eventsByTarget).forEach(([target, eventNames]) => {
            stateTransitions.push(`    ${state}-->|${eventNames.join('\\n')}|${target}`);
        });
    }

    return { transitionsMermaid: stateTransitions.join('\n'), states };
};

const renderStates = (journeyMap, states) => {
    // Types
    // process - response.type = process, response.lambda = <lambda>
    // page    - response.type = page, response.pageId = 'page-id'
    // cri     - response.type = cri,
    const mermaids = states.map((state) => {
        const definition = journeyMap[state];

        switch (definition.response?.type) {
            case 'process':
                return `    ${state}(${state}\\n${definition.response.lambda}):::process`;
            case 'page':
                return failureStates.includes(state)
                    ? `    ${state}[${state}\\n${definition.response.pageId}]:::error_page`
                    : `    ${state}[${state}\\n${definition.response.pageId}]:::page`;
            case 'cri':
                return `    ${state}([${state}\\n${definition.response.criId}]):::cri`;
            case 'error':
                return `    ${state}:::error_page`
            default:
                return `    ${state}`;
        }
    });

    return { statesMermaid: mermaids.join('\n') };
};

export const render = (journeyMap, nestedJourneys, formData = new FormData()) => {
    // Copy to avoid mutating the input
    const journeyMapCopy = JSON.parse(JSON.stringify(journeyMap));
    if (formData.has('expandNestedJourneys')) {
        expandNestedJourneys(journeyMapCopy, nestedJourneys);
    }
    expandParents(journeyMapCopy);

    const { transitionsMermaid, states } = renderTransitions(journeyMapCopy, formData);
    const { statesMermaid } = renderStates(journeyMapCopy, states);

    const mermaid =
`graph LR
    classDef process fill:#ffa,stroke:#330;
    classDef page fill:#ae8,stroke:#050;
    classDef error_page fill:#f99,stroke:#500;
    classDef cri fill:#faf,stroke:#303;
${statesMermaid}
${transitionsMermaid}
`;

    return mermaid;
};
