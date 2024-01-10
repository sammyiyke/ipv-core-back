const initialStates = ['INITIAL_IPV_JOURNEY'];
const errorStates = ['ERROR', 'CRI_TICF_BEFORE_ERROR'];
const failureStates = ['PYI_KBV_FAIL', 'PYI_NO_MATCH', 'PYI_ANOTHER_WAY', 'CRI_TICF_BEFORE_NO_MATCH', 'CRI_TICF_BEFORE_ANOTHER_WAY'];

const addDefinitionOptions = (definition, disabledOptions, featureFlagOptions) => {
    Object.entries(definition.checkIfDisabled || {}).forEach(([opt, def]) => {
        if (!disabledOptions.includes(opt)) {
            disabledOptions.push(opt);
        }
        addDefinitionOptions(def, disabledOptions, featureFlagOptions);
    });
    Object.entries(definition.checkFeatureFlag || {}).forEach(([opt, def]) => {
        if (!featureFlagOptions.includes(opt)) {
            featureFlagOptions.push(opt);
        }
        addDefinitionOptions(def, disabledOptions, featureFlagOptions);
    });
};

// Traverse the journey map to collect the available 'disabled' and 'featureFlag' options
export const getOptions = (journeyMap) => {
    const disabledOptions = [];
    const featureFlagOptions = [];

    Object.values(journeyMap).forEach((definition) => {
        const events = definition.events || definition.exitEvents || {};
        Object.values(events).forEach((def) => {
            addDefinitionOptions(def, disabledOptions, featureFlagOptions);
        });
    });

    return { disabledOptions, featureFlagOptions };
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
    // Look for an override for disabled CRIs
    const disabledCris = formData.getAll('disabledCri');
    const disabledResolution = Object.keys(definition.checkIfDisabled || {}).find((k) => disabledCris.includes(k));
    if (disabledResolution) {
        return resolveEventTarget(definition.checkIfDisabled[disabledResolution], formData);
    }

    // Look for an override for feature flags
    const featureFlags = formData.getAll('featureFlag');
    const featureFlagResolution = Object.keys(definition.checkFeatureFlag || {}).find((k) => featureFlags.includes(k));
    if (featureFlagResolution) {
        return resolveEventTarget(definition.checkFeatureFlag[featureFlagResolution], formData);
    }

    return definition.targetState;
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

const renderClickHandler = (state, definition) => {
    if (!definition.response) {
        definition.response = {};
    }
    // Click handler serializes the definition to Base64-encoded JSON to avoid escaping issues
    return `    click ${state} call onStateClick(${JSON.stringify(state)}, ${btoa(JSON.stringify(definition.response))})`;
};

const renderState = (state, definition) => {
    // Types
    // process - response.type = process, response.lambda = <lambda>
    // page    - response.type = page, response.pageId = 'page-id'
    // cri     - response.type = cri,
    switch (definition.response?.type) {
        case 'process':
            return `    ${state}(${state}\\n${definition.response.lambda}):::process`;
        case 'page':
            return failureStates.includes(state)
                ? `    ${state}[${state}\\n${definition.response.pageId}]:::error_page`
                : `    ${state}[${state}\\n${definition.response.pageId}]:::page`;
        case 'cri':
            const contextInfo = definition.response.context ? `\\n context: ${definition.response.context}` : "";
            const scopeInfo = definition.response.scope ? `\\n scope: ${definition.response.scope}` : "";
            return `    ${state}([${state}\\n${definition.response.criId}${contextInfo}${scopeInfo}]):::cri`;
        case 'error':
            return `    ${state}:::error_page`;
        default:
            return `    ${state}:::other`;
    }
};

const renderStates = (journeyMap, states) => {
    const mermaids = states.flatMap((state) => {
        const definition = journeyMap[state];
        return [
            renderState(state, definition),
            renderClickHandler(state, definition),
        ];
    });

    return { statesMermaid: mermaids.join('\n') };
};

const resolveAllPossibleEventTargets = (eventDefinition) => [
    eventDefinition.targetState,
    ...Object.values(eventDefinition.checkIfDisabled || {}).flatMap(resolveAllPossibleEventTargets),
    ...Object.values(eventDefinition.checkFeatureFlag || {}).flatMap(resolveAllPossibleEventTargets)
];

const calcOrphanStates = (journeyMap) => {
    const targetedStates = [
        ...initialStates,
        ...Object.values(journeyMap).flatMap((stateDefinition) => [
            ...Object.values(stateDefinition.events || {}).flatMap(resolveAllPossibleEventTargets),
            ...Object.values(stateDefinition.exitEvents || {}).flatMap(resolveAllPossibleEventTargets)
        ])
    ]

    const uniqueTargetedStates = [...new Set(targetedStates)];

    return Object.keys(journeyMap).filter(state => !uniqueTargetedStates.includes(state));
};

export const render = (journeyMap, nestedJourneys, formData = new FormData()) => {
    // Copy to avoid mutating the input
    const journeyMapCopy = JSON.parse(JSON.stringify(journeyMap));
    if (formData.has('expandNestedJourneys')) {
        expandNestedJourneys(journeyMapCopy, nestedJourneys);
    }
    expandParents(journeyMapCopy);

    const { transitionsMermaid, states } = formData.has('onlyOrphanStates')
        ? { transitionsMermaid: '', states: calcOrphanStates(journeyMapCopy) }
        : renderTransitions(journeyMapCopy, formData);

    const { statesMermaid } = renderStates(journeyMapCopy, states);

    // These styles should be kept in sync with the key in style.css
    const mermaid =
`graph LR
    classDef process fill:#ffa,stroke:#000;
    classDef page fill:#ae8,stroke:#000;
    classDef error_page fill:#f99,stroke:#000;
    classDef cri fill:#faf,stroke:#000;
    classDef other fill:#f3f2f1,stroke:#000;
${statesMermaid}
${transitionsMermaid}
`;

    return mermaid;
};
