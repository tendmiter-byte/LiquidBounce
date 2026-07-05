<script lang="ts">
    import {onDestroy, onMount} from "svelte";
    import {
        getTargetLockTemporaryTargets,
        getModuleSettings,
        removeTargetLockTemporaryTarget,
        setModuleSettings,
        setModuleEnabled,
    } from "../../integration/rest";
    import type {ChoiceSetting, ConfigurableSetting, ModuleSetting, TargetLockTemporaryTarget} from "../../integration/types";
    import GenericSetting from "./setting/common/GenericSetting.svelte";
    import RemovableItem from "./setting/common/RemovableItem.svelte";
    import ExpandArrow from "./setting/common/ExpandArrow.svelte";
    import {slide} from "svelte/transition";
    import {quintOut} from "svelte/easing";
    import {description as descriptionStore, highlightModuleName} from "./clickgui_store";
    import {setItem} from "../../integration/persistent_storage";
    import {convertToSpacedString, spaceSeperatedNames} from "../../theme/theme_config";
    import {scaleFactor} from "./clickgui_store";
    import {listen} from "../../integration/ws";

    export let name: string;
    export let enabled: boolean;
    export let description: string;
    export let aliases: string[];

    let moduleNameElement: HTMLElement;
    let configurable: ConfigurableSetting;
    const path = `clickgui.${name}`;
    const staticTargetsPath = `${path}.adaptiveStaticTargets`;
    let expanded = false;
    let mounted = false;
    let staticTargetsExpanded = true;
    let hasSettings = false;
    let temporaryTargets: TargetLockTemporaryTarget[] = [];
    let temporaryTargetPollTimer: ReturnType<typeof setInterval> | null = null;
    let targetLockModeSetting: ChoiceSetting | undefined;
    let targetLockStaticFilterTypeSetting: ModuleSetting | undefined;
    let targetLockStaticUsernamesSetting: ModuleSetting | undefined;
    $: targetLockModeSetting = name === "TargetLock"
        ? (configurable?.value.find((setting) => setting.name === "Mode" && setting.valueType === "CHOICE") as
            ChoiceSetting | undefined)
        : undefined;
    $: showTemporaryTargets = targetLockModeSetting?.active === "Adaptive";
    $: targetLockStaticFilterTypeSetting = showTemporaryTargets
        ? (targetLockModeSetting?.choices?.Filter?.value as ModuleSetting[] | undefined)
            ?.find((setting) => setting.name === "FilterType")
        : undefined;
    $: targetLockStaticUsernamesSetting = showTemporaryTargets
        ? (targetLockModeSetting?.choices?.Filter?.value as ModuleSetting[] | undefined)
            ?.find((setting) => setting.name === "Usernames")
        : undefined;

    onMount(async () => {
        staticTargetsExpanded = localStorage.getItem(staticTargetsPath) !== "false";
        await fetchModuleSettings();

        setTimeout(() => {
            expanded = localStorage.getItem(path) === "true"
        }, 500);
        mounted = true;
    });

    onDestroy(() => {
        stopTemporaryTargetPolling();
    });

    highlightModuleName.subscribe((m) => {
        if (name !== m) {
            return;
        }

        setTimeout(() => {
            if (!moduleNameElement) {
                return;
            }
            moduleNameElement.scrollIntoView({
                behavior: "smooth",
                block: "center",
            });
        }, 1000);
    });

    async function fetchModuleSettings() {
        try {
            configurable = await getModuleSettings(name);
            hasSettings = configurable.value.filter(v => v.name !== "Bind" && v.name !== "Hidden").length > 0;
        } catch (err) {
            console.error("Failed to fetch module settings", err);
        }
    }

    async function fetchTemporaryTargets() {
        if (!showTemporaryTargets || !expanded) {
            return;
        }

        try {
            temporaryTargets = await getTargetLockTemporaryTargets();
        } catch (err) {
            console.error("Failed to fetch temporary targets", err);
        }
    }

    function startTemporaryTargetPolling() {
        if (temporaryTargetPollTimer !== null || !showTemporaryTargets) {
            return;
        }

        fetchTemporaryTargets();
        temporaryTargetPollTimer = setInterval(fetchTemporaryTargets, 1000);
    }

    function stopTemporaryTargetPolling() {
        if (temporaryTargetPollTimer !== null) {
            clearInterval(temporaryTargetPollTimer);
            temporaryTargetPollTimer = null;
        }

        if (temporaryTargets.length > 0) {
            temporaryTargets = [];
        }
    }

    async function removeTemporaryTarget(username: string) {
        try {
            await removeTargetLockTemporaryTarget(username);
            await fetchTemporaryTargets();
        } catch (err) {
            console.error("Failed to remove temporary target", err);
        }
    }

    function formatRemainingTime(seconds: number) {
        const remainingSeconds = Math.max(0, Math.ceil(seconds));
        const minutes = Math.floor(remainingSeconds / 60);
        const secondsPart = remainingSeconds % 60;

        if (minutes <= 0) {
            return `${secondsPart}s`;
        }

        return `${minutes}m ${secondsPart.toString().padStart(2, "0")}s`;
    }

    $: if (expanded && showTemporaryTargets) {
        startTemporaryTargetPolling();
    } else {
        stopTemporaryTargetPolling();
    }

    $: if (mounted) {
        setItem(staticTargetsPath, staticTargetsExpanded.toString());
    }

    let refetchTimer: ReturnType<typeof setTimeout> | null = null;

    async function updateModuleSettings() {
        try {
            await setModuleSettings(name, configurable);
            if (showTemporaryTargets) {
                await fetchTemporaryTargets();
            }
            // Debounce the refetch so rapid slider changes (e.g. holding an arrow key)
            // don't fire a GET that races back and overwrites the current local value
            // with a stale one, causing the slider to snap back.
            if (refetchTimer !== null) clearTimeout(refetchTimer);
            refetchTimer = setTimeout(async () => {
                refetchTimer = null;
                await fetchModuleSettings();
            }, 150);
        } catch (err) {
            console.error("Failed to update module settings", err);
        }
    }

    async function toggleModule() {
        await setModuleEnabled(name, !enabled);
    }

    function setDescription() {
        if (!moduleNameElement) return;

        const boundingRect = moduleNameElement.getBoundingClientRect();
        const y = (boundingRect.top + (moduleNameElement.clientHeight / 2)) * (2 / $scaleFactor);

        let moduleDescription = description;
        if (aliases.length > 0) {
            moduleDescription += ` (aka ${aliases.map(name => $spaceSeperatedNames ? convertToSpacedString(name) : name).join(", ")})`;
        }

        // If element is less than 300px from the right, display description on the left
        if (window.innerWidth - boundingRect.right > 300) {
            const x = boundingRect.right * (2 / $scaleFactor);
            descriptionStore.set({
                x,
                y,
                anchor: "right",
                description: moduleDescription
            });
        } else {
            const x = boundingRect.left * (2 / $scaleFactor);

            descriptionStore.set({
                x,
                y,
                anchor: "left",
                description: moduleDescription
            });
        }
    }

    async function toggleExpanded() {
        expanded = !expanded;
        if (expanded) {
            await fetchModuleSettings();
        }
        await setItem(path, expanded.toString());
    }

    function isEqual(a: any, b: any): boolean {
        if (a === b) return true;
        if (a == null || b == null) return false;
        if (typeof a !== typeof b) return false;
        if (typeof a === "object") {
            if (Array.isArray(a) && Array.isArray(b)) {
                if (a.length !== b.length) return false;
                for (let i = 0; i < a.length; i++) {
                    if (!isEqual(a[i], b[i])) return false;
                }
                return true;
            }
            const keysA = Object.keys(a);
            const keysB = Object.keys(b);
            if (keysA.length !== keysB.length) return false;
            for (const k of keysA) {
                if (!isEqual(a[k], b[k])) return false;
            }
            return true;
        }
        return false;
    }

    listen("valueChanged", (e) => {
        if (configurable) {
            const findSetting = (settings: ModuleSetting[]): ModuleSetting | null => {
                for (const s of settings) {
                    if (s.name === e.value.name) {
                        return s;
                    }
                    if ((s.valueType === "CHOICE" || s.valueType === "TOGGLEABLE" || s.valueType === "CONFIGURABLE") && Array.isArray(s.value)) {
                        const found = findSetting(s.value as ModuleSetting[]);
                        if (found) return found;
                    }
                    if (s.valueType === "CHOICE" && (s as any).choices) {
                        for (const choice of Object.values((s as any).choices) as ModuleSetting[]) {
                            if (choice.value && Array.isArray(choice.value)) {
                                const found = findSetting(choice.value as ModuleSetting[]);
                                if (found) return found;
                            }
                        }
                    }
                }
                return null;
            };
            const localSetting = findSetting(configurable.value);
            if (localSetting) {
                if (!isEqual(localSetting.value, e.value.value)) {
                    fetchModuleSettings();
                }
            }
        }
    });
</script>

<!-- svelte-ignore a11y-no-static-element-interactions -->
<div
        class="module"
        class:expanded
        class:has-settings={hasSettings}
        in:slide={{ duration: 500, easing: quintOut }}
        out:slide={{ duration: 500, easing: quintOut }}
>
    <!-- svelte-ignore a11y-click-events-have-key-events -->
    <div
            class="name"
            on:contextmenu|preventDefault={toggleExpanded}
            on:click={toggleModule}
            on:mouseenter={setDescription}
            on:mouseleave={() => descriptionStore.set(null)}
            bind:this={moduleNameElement}
            class:enabled
            class:highlight={name === $highlightModuleName}
    >
        {$spaceSeperatedNames ? convertToSpacedString(name) : name}
    </div>

    {#if expanded && configurable}
        <div class="settings">
            {#each configurable.value as setting (setting.name)}
                <GenericSetting {path} bind:setting on:change={updateModuleSettings}/>
            {/each}

            {#if showTemporaryTargets}
                {#if targetLockStaticFilterTypeSetting || targetLockStaticUsernamesSetting}
                    <div class="target-lock-static-targets">
                        <div class="target-lock-section-head">
                            <div class="target-lock-section-title">Static Targets</div>
                            <ExpandArrow bind:expanded={staticTargetsExpanded}/>
                        </div>

                        {#if staticTargetsExpanded}
                            <div class="target-lock-section-body" transition:slide|global={{duration: 200, axis: "y"}}>
                                {#if targetLockStaticFilterTypeSetting}
                                    <GenericSetting
                                            path={`${path}.Mode.Filter`}
                                            setting={targetLockStaticFilterTypeSetting}
                                            on:change={updateModuleSettings}
                                    />
                                {/if}
                                {#if targetLockStaticUsernamesSetting}
                                    <GenericSetting
                                            path={`${path}.Mode.Filter`}
                                            setting={targetLockStaticUsernamesSetting}
                                            on:change={updateModuleSettings}
                                    />
                                {/if}
                            </div>
                        {/if}
                    </div>
                {/if}

                <div class="temporary-targets">
                    <div class="target-lock-section-title">Temporary Targets</div>
                    {#if temporaryTargets.length > 0}
                        <div class="temporary-target-list">
                            {#each temporaryTargets as target (target.username)}
                                <RemovableItem on:remove={() => removeTemporaryTarget(target.username)}>
                                    <div class="temporary-target">
                                        <span class="temporary-target-name">{target.username}</span>
                                        <span class="temporary-target-expiry">{formatRemainingTime(target.remainingSeconds)}</span>
                                    </div>
                                </RemovableItem>
                            {/each}
                        </div>
                    {:else}
                        <div class="temporary-target-empty">No temporary targets</div>
                    {/if}
                </div>
            {/if}
        </div>
    {/if}
</div>

<style lang="scss">
  @use "./icon-settings-expand" as *;

  .module {
    position: relative;

    .name {
      cursor: pointer;
      transition: ease background-color 0.2s,
      ease color 0.2s;

      color: var(--clickgui-text-dimmed-color);
      text-align: center;
      font-size: 12px;
      font-weight: 500;
      position: relative;
      padding: 10px;

      &.highlight::before {
        content: "";
        position: absolute;
        top: 0;
        left: 0;
        width: calc(100% - 4px);
        height: calc(100% - 4px);
        border: solid 2px var(--clickgui-module-highlight-color);
      }

      &:hover {
        background-color: var(--clickgui-module-hover-background-color);
        color: var(--clickgui-text-color);
      }

      &.enabled {
        color: var(--clickgui-module-enabled-color);
      }
    }

    .settings {
      background-color: var(--clickgui-module-settings-background-color);
      border-left: solid 4px var(--clickgui-module-settings-border-color);
      padding: 0 11px 0 7px;
    }

    .target-lock-static-targets,
    .temporary-targets {
      padding: 7px 0 10px;
    }

    .target-lock-section-head {
      display: grid;
      grid-template-columns: minmax(0, 1fr) max-content;
      align-items: center;
      margin-bottom: 7px;
    }

    .target-lock-section-title {
      color: var(--clickgui-text-color);
      font-size: 12px;
      font-weight: 600;
    }

    .temporary-target-list {
      display: flex;
      flex-direction: column;
      row-gap: 7px;
    }

    .temporary-target {
      display: grid;
      grid-template-columns: minmax(0, 1fr) max-content;
      column-gap: 8px;
      align-items: center;
      width: 100%;
      min-height: 25px;
      padding: 4px 6px;
      border-radius: 3px;
      background-color: var(--clickgui-input-background-color);
      border-bottom: solid 2px var(--clickgui-input-border-color);
    }

    .temporary-target-name {
      color: var(--clickgui-text-color);
      font-family: monospace;
      font-size: 12px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .temporary-target-expiry,
    .temporary-target-empty {
      color: var(--clickgui-text-dimmed-color);
      font-size: 12px;
    }

    &.has-settings {
      .name::after {
        @include icon-settings-expand($right: 15px);
        opacity: 0.5;
      }

      &.expanded .name::after {
        transform: translateY(-50%) rotate(0);
        opacity: 1;
      }
    }
  }
</style>
