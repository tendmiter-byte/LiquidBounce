<script lang="ts">
    import {createEventDispatcher, onMount} from "svelte";
    import type {BooleanSetting as TBooleanSetting, ModuleSetting, TogglableSetting,} from "../../../integration/types";
    import ExpandArrow from "./common/ExpandArrow.svelte";
    import GenericSetting from "./common/GenericSetting.svelte";
    import Switch from "./common/Switch.svelte";
    import {setItem} from "../../../integration/persistent_storage";
    import {convertToSpacedString, spaceSeperatedNames} from "../../../theme/theme_config";

    export let setting: ModuleSetting;
    export let path: string;

    $: cSetting = setting as TogglableSetting;
    $: thisPath = `${path}.${cSetting.name}`;

    const dispatch = createEventDispatcher();

    $: enabledSetting = (cSetting.value && cSetting.value[0]) as TBooleanSetting;

    $: nestedSettings = cSetting.value ? cSetting.value.slice(1) : [];

    let expanded = false;
    let mounted = false;

    onMount(() => {
        expanded = localStorage.getItem(thisPath) === "true";
        mounted = true;
    });

    $: if (mounted) {
        setItem(thisPath, expanded.toString());
    }

    function handleChange() {
        setting = { ...cSetting };
        dispatch("change");
    }

    function toggleExpanded() {
        expanded = !expanded;
    }
</script>

<div class="setting">
    {#if enabledSetting}
        {#if nestedSettings.length > 0}
            <!-- svelte-ignore a11y-no-static-element-interactions -->
            <div class="head expand" class:expanded on:contextmenu|preventDefault={toggleExpanded}>
                <Switch
                    name={$spaceSeperatedNames ? convertToSpacedString(cSetting.name) : cSetting.name}
                    bind:value={enabledSetting.value}
                    on:change={handleChange}
                />
                <ExpandArrow bind:expanded />
            </div>
        {:else}
            <div class="head" class:expanded>
                <Switch
                    name={$spaceSeperatedNames ? convertToSpacedString(cSetting.name) : cSetting.name}
                    bind:value={enabledSetting.value}
                    on:change={handleChange}
                />
            </div>
        {/if}
    {/if}

    {#if expanded && nestedSettings.length > 0}
        <div class="nested-settings">
            {#each nestedSettings as setting (setting.name)}
                <GenericSetting  path={thisPath} bind:setting on:change={handleChange} />
            {/each}
        </div>
    {/if}
</div>

<style lang="scss">

    .setting {
        padding: 7px 0px;
    }

    .head {
        transition: ease margin-bottom .2s;

        &.expand {
          display: grid;
          grid-template-columns: 1fr max-content;
        }

        &.expanded {
            margin-bottom: 10px;
        }
    }

    .nested-settings {
        border-left: solid 2px var(--clickgui-setting-group-border-color);
        padding-left: 7px;
    }
</style>
