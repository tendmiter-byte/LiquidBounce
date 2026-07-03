<script lang="ts">
    import {onMount} from "svelte";
    import {
        getModuleSettings,
        setModuleSettings,
        setModuleEnabled,
    } from "../../integration/rest";
    import type {ConfigurableSetting, ModuleSetting} from "../../integration/types";
    import GenericSetting from "./setting/common/GenericSetting.svelte";
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
    let expanded = false;
    let hasSettings = false;

    onMount(async () => {
        await fetchModuleSettings();

        setTimeout(() => {
            expanded = localStorage.getItem(path) === "true"
        }, 500);
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

    let refetchTimer: ReturnType<typeof setTimeout> | null = null;

    async function updateModuleSettings() {
        try {
            await setModuleSettings(name, configurable);
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
