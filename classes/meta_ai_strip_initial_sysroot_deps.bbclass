# Strips *-initial do_populate_sysroot nodes (e.g. libgcc-initial,
# glib-2.0-initial) out of BB_TASKDEPDATA before oe-core's
# extend_recipe_sysroot runs, on every task of the inheriting recipe that
# would otherwise receive it.
#
# Root cause: any task whose [depends] flag contains the substring
# "populate_sysroot" gets extend_recipe_sysroot auto-attached as a prefunc
# (staging.bbclass, staging_taskhandler). That function walks the task's full
# BB_TASKDEPDATA to build a per-recipe sysroot. The "-initial" bootstrap
# providers (used only to build glibc, fully superseded by their real
# counterparts afterwards) are normally kept out of a sysroot by
# setscene_depvalid()'s SSTATE_EXCLUDEDEPS_SYSROOT filtering - but that
# filtering only fires when the *consuming* task is itself do_populate_sysroot
# (sstate.bbclass), which is never true for do_image_oci, do_image_complete,
# do_install, do_package, etc. So both an "-initial" provider and its real
# counterpart get exposed into the same sysroot and collide on a shared file
# (crtbegin.o, gunixinputstream.h, ...), aborting the task while every other
# tool involved reports success.
#
# oe-core's own staging_populate_sysroot_dir (a different, shared-sysroot
# codepath) already skips "-initial" manifests for exactly this reason
# ("skip libgcc-initial due to file overlap"); this class gives the
# per-recipe extend_recipe_sysroot path the same treatment, scoped to
# whichever recipe inherits it.
#
# Use: `inherit meta_ai_strip_initial_sysroot_deps` in any recipe whose tasks
# pull in a full toolchain/library closure (e.g. via a do_image_complete or
# similar dependency) without needing that closure's own build-time sysroot
# content.

python meta_ai_strip_initial_from_taskdepdata () {
    import copy

    taskdepdata = d.getVar("BB_TASKDEPDATA", False)
    if not taskdepdata:
        return

    filtered = copy.deepcopy(taskdepdata)
    drop = set()
    for k, v in filtered.items():
        # v[0] = PN, v[1] = taskname
        if v[1] == "do_populate_sysroot" and v[0].endswith("-initial"):
            drop.add(k)

    if not drop:
        return

    for k in drop:
        del filtered[k]
    # Each entry is an immutable bb.TaskData namedtuple, but its dep set
    # (index 3) is mutable, so prune inbound edges in place rather than
    # reassigning the field.
    for v in filtered.values():
        v[3].difference_update(drop)

    bb.note("%s: dropped %d *-initial do_populate_sysroot node(s) from "
            "BB_TASKDEPDATA to avoid a libgcc/libgcc-initial-style sysroot "
            "collision" % (d.getVar("PN"), len(drop)))
    d.setVar("BB_TASKDEPDATA", filtered)
}
meta_ai_strip_initial_from_taskdepdata[vardepsexclude] += "BB_TASKDEPDATA"

# Register after oe-core's staging_taskhandler (this handler fires later, so
# its prependVarFlag lands ahead of the earlier prepend). Excludes the same
# two tasks staging_taskhandler itself excludes.
python meta_ai_strip_initial_taskhandler() {
    EXCLUDED_TASKS = ("do_prepare_recipe_sysroot", "do_create_spdx")
    for task in e.tasklist:
        if task in EXCLUDED_TASKS:
            continue
        deps = d.getVarFlag(task, "depends")
        if task == "do_configure" or (deps and "populate_sysroot" in deps):
            d.prependVarFlag(task, "prefuncs",
                             "meta_ai_strip_initial_from_taskdepdata ")
}
meta_ai_strip_initial_taskhandler[eventmask] = "bb.event.RecipeTaskPreProcess"
addhandler meta_ai_strip_initial_taskhandler
