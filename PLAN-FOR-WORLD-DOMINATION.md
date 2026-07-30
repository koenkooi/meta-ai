# PLAN FOR WORLD DOMINATION

...or, less ambitiously: a working reference integration that ships AI runtimes as OCI
containers built by bitbake, on a real Qualcomm board, with genuine cross-image layer
deduplication on the target — and one of them serving an LLM over HTTP out of the box.

This is the design document for the `full-integration-test` branch of `koenkooi/meta-ai`.
It describes what the branch builds, why it is built this way, what has been verified,
what is known not to work, and where someone picking this up should go next.

## What this branch is

`meta-ai` upstream is a collection of standalone AI-runtime recipes (LiteRT, ONNX Runtime,
TensorFlow Lite, ONNX, llama.cpp). It has no image recipes, no machine integration, and no
container content. This branch turns it into a complete board integration for Qualcomm's
`rb1-core-kit` (QRB2210/QCM2290, aarch64):

- A **flashable primary image** (`meta-ai-container-host-image`) — `core-image-base` plus a
  full container stack (docker-moby, containerd, runc), the Qualcomm CDI generator, and the
  CNCF `cdi` CLI.
- **Four AI-runtime OCI container images**, each built as an ordinary bitbake image recipe,
  sharing a common base layer. Three are library-only; the fourth runs llama.cpp's
  `llama-server` with a model baked in.
- A **boot-time import mechanism** that loads all four into Docker on the target, with
  stable names, from archives shipped inside the primary rootfs.

Everything is produced by one bitbake invocation:

```
kas-container shell meta-ai/ci/rb1-core-kit.yml -c "bitbake meta-ai-container-host-image"
```

The containers are recipe dependencies of the primary image, so ordinary bitbake dependency
resolution builds them in the right order. There is no second build step, no external
container tooling, and no registry involved at build time.

## Why containers rather than rootfs packages

Baking the AI runtimes into a base rootfs means every image carries all of them, updates are
coupled to rootfs updates, and there is no isolation between them. Shipping them as OCI
images means each runtime is independently loadable, independently replaceable, and can be
handed to an application container by reference. The Qualcomm CDI generator is in the same
image for the natural companion reason: it writes CDI JSON specs to `/run/cdi` at boot so
Qualcomm devices (GPU/NPU/FastRPC) can be passed into those containers.

The cost of that choice is duplication — runtimes that all link glibc, libstdc++ and abseil
would each carry their own copy. The bulk of the engineering on this branch went into making
that cost not real, which is what the layer-sharing design below is for.

## Architecture

### Container images

Five image recipes under `recipes-containers/images/`, all using `meta-virtualization`'s
`image-oci.bbclass`:

```
meta-ai-container-base-oci        shared base layer
  ├─ meta-ai-litert-oci           + litert                     library-only
  ├─ meta-ai-onnxruntime-oci      + onnxruntime                library-only
  ├─ meta-ai-tensorflow-lite-oci  + tensorflow-lite            library-only
  └─ meta-ai-llama-cpp-oci        + llama-server + GGUF model  runnable
```

The base image installs `base-files`, `base-passwd`, `netbase`, `glibc`, `libgcc` and four
abseil libraries. `libstdc++` is not named explicitly (see "Known limitations") but arrives
transitively via abseil's `RDEPENDS`, so it ends up in the shared layer anyway.

Each app image sets:

```
OCI_BASE_IMAGE = "meta-ai-container-base-oci"
OCI_LAYER_MODE = "multi"
OCI_LAYERS = "litert:packages:litert"
```

`OCI_LAYER_MODE = "multi"` is the load-bearing setting. In the default single-layer mode,
`image-oci.bbclass` rsyncs an image's entire `IMAGE_ROOTFS` into one new layer on top of the
base's layers, with no per-file awareness of what the base already provides — so a shared
base cannot reduce anything. In multi-layer mode, each `packages:` entry is installed by opkg
into its own scratch rootfs which is then merged into the already-unpacked base bundle, and
`umoci repack` diffs the result — which means content already present in the base *can*
deduplicate, provided the merge step doesn't gratuitously perturb it. Making that actually
happen required an upstream fix (patch 0003, below).

The three library-only images set no entrypoint. The shared base has no shell, and these are
intended for bind-mount consumption by an application container, not for `docker run`.

### The runnable image: llama.cpp

`meta-ai-llama-cpp-oci` is the exception, and demonstrates the design carrying a real
workload rather than just libraries. It is directly runnable:

```
OCI_LAYERS = "llama-cpp:packages:busybox+catatonit+llama-cpp \
              model:directories:${LLAMA_MODEL_DIR}"
OCI_IMAGE_ENTRYPOINT = "/usr/bin/catatonit --"
OCI_IMAGE_PORTS = "8080/tcp"
```

`catatonit` is PID 1 (a container init shim that reaps zombies and forwards signals); the
`--` separates catatonit's own flags from the program it supervises. `OCI_IMAGE_CMD` invokes
`/usr/bin/llama-server` with `--host 0.0.0.0`, the model path, `--ctx-size 16384` and the
generation parameters, so `docker run -p 8080:8080` yields a working OpenAI-compatible API
and web UI with no arguments.

The model — Unsloth's Qwen3.5-0.8B BF16 GGUF, small enough to build and boot-test quickly —
comes from `huggingface-model.bbclass` in bradfa's `meta-bradfa-ai-distro`
(`oe-ai-playground`), added to `ci/base.yml` as a full layer rather than vendored. Swapping
in a larger model is a bbappend away and touches nothing else in the recipe.

**The model cannot go through `OCI_LAYERS`' `packages:` type.**
`huggingface-model.bbclass` ships models via `do_deploy` with a deliberately *empty*
package, because IPK/DEB's `ar` container format caps an individual member at ~9.3 GB — so
there is no real package for opkg to install into a layer. The recipe instead takes a
`do_rootfs[depends]` on the model's `do_deploy` and a `ROOTFS_POSTPROCESS_COMMAND` that
copies the model from `DEPLOY_DIR_IMAGE` into `IMAGE_ROOTFS`, where the
`model:directories:` layer entry then picks it up. Keeping the model in its own layer also
means it is addressed by its own digest, so replacing the binary does not re-transfer 1.4 GiB
of weights.

`llama-cpp_b8902.bb` needed one change: `-DLLAMA_BUILD_WEBUI=ON`. Built `OFF`, `llama-server`
starts and serves the API but has no embedded frontend assets, so `GET /` returns
`HTTP/1.1 404 Not Found`. Finding out why that fix appeared to have no effect is what
surfaced upstream bug 0004, below.

### Boot-time import

`recipes-containers/meta-ai-oci-import/meta-ai-oci-import_1.0.bb` ships the four built OCI
archives into `/usr/share/meta-ai/<app>-latest-oci.tar` and installs a templated systemd
oneshot (`meta-ai-oci-import@.service`) instantiated once per app, plus a shell wrapper
(`meta-ai-oci-import.sh`). Each instance runs `docker load` on its own archive and retags the
result.

Two non-obvious properties of that script are worth stating, because both were found the hard
way and both are inherent rather than incidental:

**The retag is necessary.** The OCI image spec has no repository field — only a tag ref
(`org.opencontainers.image.ref.name`). A bare OCI archive therefore loads as `latest:latest`,
because docker has nothing else to name the repository with. This is not a defect in the
recipe or in `image-oci.bbclass`; `docker save`'s docker-format archives carry an extra
`repositories` file that OCI archives simply don't have. Setting `OCI_IMAGE_TAG` to a
`name:tag` string does not help — the class also writes that string into the deploy archive's
filename. So the script resolves the loaded image's ID (from `docker load`'s own output, with
two fallbacks for older output formats) and retags by ID to `meta-ai-<app>:latest`. Tagging by
the `latest:latest` name does not work on this docker version's containerd-backed image store;
only tagging by raw ID does.

**The imports must be serialized.** They are independent systemd template instances with no
ordering between them, so all four `docker load` calls would otherwise run concurrently
against containerd's shared content store — and they share the base layer's blob. Concurrent
loads intermittently fail mid-unpack with `content digest ... not found`, and the losing
instance's retag can point at whichever image finished last. The script takes a `mkdir`-based
lock in `/run` (not `flock`, which isn't guaranteed to be packaged into a minimal rootfs).
This makes first boot slower — the llama-cpp archive alone is ~1.4 GiB and takes over a
minute to load on the qemu proxy — but correct.

### Primary image

`recipes-core/images/meta-ai-container-host-image.bb` is `core-image-base` plus
`docker qualcomm-cdi-generator cdi meta-ai-oci-import`. From the primary image's point of
view the four AI-runtime containers are opaque payload files and one systemd unit; it has no
knowledge of their contents.

`recipes-containers/qualcomm-cdi-generator/qualcomm-cdi-generator_0.4.bb` is a new recipe for
`qualcomm-linux/qualcomm-CDI-generator` v0.4 (Python/Meson, BSD-3-Clause), shipping the
generator script and its systemd oneshot. `cdi` and `docker`/`containerd`/`runc` all come from
`meta-virtualization`.

### kas configuration

`ci/base.yml` pins the layer set (`openembedded-core`, `bitbake`, five `meta-openembedded`
sublayers, `meta-qcom`, `meta-virtualization`, `oe-ai-playground`) on floating `master`,
matching `meta-ai`'s existing `kas/` convention — no lockfile. `ci/rb1-core-kit.yml` adds
`machine: rb1-core-kit`; `ci/qemuarm64.yml` adds `machine: qemuarm64` plus autologin image
features, and exists purely as a bootable proxy for verification.

Configuration details in `base.yml` that are load-bearing rather than cosmetic:

- `SKIP_META_VIRT_SANITY_CHECK = "1"` and `DISTRO_FEATURES:append = " virtualization seccomp
  ipv6"` — docker-moby requires `seccomp` and `ipv6`.
- `IMAGE_ROOTFS_EXTRA_SPACE = "4194304"` — the boot-time `docker load` has to unpack the
  runtime layers into containerd's snapshot store, which the default rootfs sizing has no
  headroom for. Without this, first boot fails with `no space left on device` mid-unpack.
  4 GB rather than the 512 MB that sufficed for the three library-only images: llama-cpp's
  archive is ~1.4 GiB on its own, and docker unpacks all four into `/var/lib/docker`.
  Build-time OCI packing needs no such headroom, so this only shows up on target.
- `RM_WORK_EXCLUDE += "containerd cdi"` — Go's module cache under `pkg/mod` is deliberately
  read-only, and `rm_work`'s plain `rm -rf` cannot remove it. `do_package` succeeds first, so
  the failure is purely post-build cleanup, but bitbake still blocks `do_build` on it and it
  recurs on every retry.
- `BBMASK` for two orphaned `meta-qcom` bbappends (weston, android-tools) that reference base
  recipes present only in the full Qualcomm graphics/BSP layer set.
- `BBMASK` for four `meta-virtualization` recipes unused by this target — `cosign`, `incus`,
  `k3s`, `yq` — which fail oe-core's license-format QA check on floating `master` (duplicated
  `AND` clauses in `LICENSE`). Masking is preferable to pinning a lockfile for recipes nothing
  here builds.
- `meta-arm`/`meta-arm-toolchain` are deliberately **not** included. Pulling them in fails at
  parse time with 185 fatal QA errors from ARM firmware recipes whose `LICENSE` fields use
  SPDX expressions this oe-core version's license parser rejects. Nothing on this branch needs
  ARM firmware content, and `meta-qcom` itself only includes its `ci/meta-arm.yml` for
  `rb3gen2-core-kit-open-fw`, not for `rb1-core-kit`.

## Four upstream bugs in `image-oci.bbclass`

Getting the layer-sharing design to work required fixing four real bugs in
`meta-virtualization`'s `image-oci.bbclass`. The first three are carried as patch files under
`patches/` and applied by kas's native `patches:` mechanism from `ci/base.yml`, so
`meta-virtualization` still tracks upstream `master` and each patch is dropped by deleting
three lines of YAML once it lands upstream. The fourth is diagnosed but not yet patched.

**0001 — multi-layer `packages:` installs never call `pm.update()`.**
`oci_install_layer_packages()` creates a per-layer package manager, calls `pm.write_index()`,
then `pm.install()` — skipping `pm.update()`. opkg does not load a just-written `Packages`
index into its in-process hash on a fresh `--volatile-cache` invocation; only `opkg update`
does that. So the install fails, and because the exception handler only `bb.warn()`s,
`do_image_oci` reports success while shipping a layer that is just the empty rootfs skeleton.
oe-core's own `do_rootfs` path always does `write_index()` → `update()` → `install()`;
`image-oci.bbclass`'s per-layer path was missing the middle step, in both the ipk and rpm
branches. Verified with a minimal reproduction: the affected layer's cache directory went from
28 KB (empty) to 74 MB (containing the actual runtime libraries) with the fix applied.

**0002 — `do_image_oci` collides on `libgcc` vs `libgcc-initial`.**
`do_image_oci` aborts with:

```
ERROR: <recipe> do_image_oci: The file .../crtbegin.o is installed by both libgcc and libgcc-initial, aborting
```

Root cause: any task whose `[depends]` flag contains the substring
`populate_sysroot` gets oe-core's `extend_recipe_sysroot` auto-attached as a prefunc
(`staging.bbclass`'s `staging_taskhandler`), and `do_image_oci` qualifies via its native
tooling deps. That function walks `BB_TASKDEPDATA` calling `setscene_depvalid()`, whose
`SSTATE_EXCLUDEDEPS_SYSROOT` filtering — the thing that normally keeps `-initial` bootstrap
providers out of a sysroot — is gated behind `taskdependees[task][1] == 'do_populate_sysroot'`.
That is never true when the consuming task is `do_image_oci`, so the filter never fires and
both providers land in the same sysroot. No `SSTATE_EXCLUDEDEPS_SYSROOT` config rule can work
around this from the outside, because the gate makes no rule shape fire at all.

The fix adds a prefunc that strips `*-initial` `do_populate_sysroot` nodes out of
`BB_TASKDEPDATA` before `extend_recipe_sysroot` runs. This mirrors a skip that oe-core's own
`staging_populate_sysroot_dir` (a different codepath) already applies for exactly this reason.

The same bug class then surfaced in `meta-ai-oci-import` itself, at `do_install` and
`do_package`: `base.bbclass` gives every target recipe's fakeroot tasks a
`virtual/fakeroot-native:do_populate_sysroot` dependency, which is enough to trigger
`extend_recipe_sysroot`, and this recipe's own `do_install[depends]` on the app images'
`:do_image_complete` tasks drags the whole toolchain closure into the walk. The same technique
is therefore also packaged as a reusable local class,
`classes/meta_ai_strip_initial_sysroot_deps.bbclass`, inherited by all five image recipes and
by `meta-ai-oci-import`, attached to every task that would receive `extend_recipe_sysroot`
rather than only the one that happened to fail first. Any future recipe in this layer that
gives a fakeroot task an image-style dependency will need it too.

The clean general fix belongs in oe-core's `setscene_depvalid` — the `-initial` exclusion
should apply regardless of which task is consuming.

**0003 — the multi-layer merge used `rsync -a`, defeating dedup.**
This is the one that made the whole layer-sharing design actually deliver. With 0001 and 0002
fixed, the images built and booted correctly but did not deduplicate: each app's layer
contained a full copy of `libc.so.6` byte-identical to the base's.

The merge step in `oci_multilayer_install_packages()` copies the freshly opkg-installed layer
into the already-unpacked base bundle with plain `rsync -a`, which decides whether to
re-transfer a file using **size + mtime, not content**. Every package's file mtimes are baked
in at that package's own build time — `SOURCE_DATE_EPOCH` is resolved per recipe, and there is
no shared cross-recipe "image epoch" applied to these OCI scratch rootfses the way
`image.bbclass`'s `reproducible_final_image_task` normalizes a normal `IMAGE_ROOTFS` (that
hook doesn't run here at all, since these rootfses are populated outside `do_rootfs`). So the
same package installed independently for the base image and for an app image is byte-identical
with a *different* mtime. rsync's own `--itemize-changes` confirms it directly:

```
>f..t...... usr/lib/libc.so.6
```

(`>` = content transferred, `t` = time differed). `umoci repack` then correctly sees a changed
file and includes its full content in the new layer.

The fix is `rsync -a --checksum --no-times`. Both flags are required. `--checksum` alone stops
the *data* transfer but not the damage: `-a` implies `--times`, so rsync still updates the
destination's mtime, and that alone is enough for `umoci repack` to treat the file as changed.
Isolated `umoci`-only tests confirmed all three cases — `rsync -a` and `rsync -a --checksum`
both produced a full 7.5 MB duplicate blob; `rsync -a --checksum --no-times` left the base's
file untouched and `umoci` reused the existing blob.

An earlier attempt to fix this by normalizing mtimes with a custom `os.utime()` prefunc
*before* the rsync did not work, and made totals slightly worse — it addressed the wrong side
of the problem, since rsync re-syncs the destination mtime as part of normal `--times`
behavior regardless of what it started as.

**0004 — the multi-layer layer cache is keyed on package version, not content.**
`image-oci-umoci.inc`'s `oci_compute_layer_cache_key()` hashes the layer name and type, the
sorted package list, `MACHINE`, `TUNE_PKGARCH`, and each package's **`PKGV`** read from
`PKGDATA_DIR/runtime/<pkg>`; cached layer rootfses live in
`${TOPDIR}/oci-layer-cache/${MACHINE}/<key>-<layer>/`. `PKGV` is the package *version* only —
it excludes `PKGR`, the recipe's task signatures, and the package contents. So any change that
rebuilds a package without changing its version serves the stale pre-change layer. An
`EXTRA_OECMAKE` flag flip is the obvious case, and is exactly how this was found: turning on
`LLAMA_BUILD_WEBUI` grew `llama-server` from 1,575,240 to 8,579,136 bytes in the freshly
written `.ipk`, while the OCI archive kept shipping the 1,575,240-byte binary.

What makes it costly to diagnose is that the cache survives every normal invalidation route,
because it lives outside both `TMPDIR` and sstate. `cleansstate` on the image recipes,
`cleansstate` on `llama-cpp` followed by a genuine full recompile at 0% sstate reuse, and
`bitbake -c package_index -f` plus `-c rootfs -f` all left the stale layer in place. The only
tell is in `log.do_image_oci`:

```
NOTE: OCI Cache HIT: Layer 'llama-cpp' (110680f2d9800e69)
NOTE: OCI: Pre-installed packages for 1 layers (cache: 1 hits, 0 misses)
```

Two things must then happen together to rebuild the layer: delete the cache directory **and**
force the task (`bitbake -c image_oci -f`). Deleting the cache alone appears to do nothing,
because an unchanged `do_image_oci` signature is satisfied from sstate via setscene and the
cache is never consulted at all. The proper fix is to key the cache on package content or on
the recipe's task hash instead of `PKGV`, or at minimum to include `PKGR`.

Upstream submissions for 0001 and 0002 (cover letter, patches, minimal test cases) are drafted
under `reports/meta-virtualization-patchset/`. 0003 belongs in the same series or as a fast
follow-up — same file, same function family — and has not been drafted yet. 0004 has no patch
written at all.

## What is verified

The primary `rb1-core-kit` image builds clean from a fresh state (all meta-ai recipes
`cleansstate`d and the persistent `oci-layer-cache` removed — see bug 0004 for why that
second step is not optional): 7198 tasks, all succeeded, zero errors. The rootfs manifest
confirms `docker-moby`, `docker-moby-cli`, `containerd`, `runc`, `cdi`,
`qualcomm-cdi-generator` and `meta-ai-oci-import`; the deploy directory contains both the
`.ext4` rootfs and the flashable `qcomflash` bundle.

Cross-image deduplication is real and verified by digest, not inferred from sizes. All five
images' OCI manifests reference the same base layer blob
(`sha256:acc8fb90e0d4…`, 2,748,077 B), and that blob is the one containing `libc.so.6`,
`libgcc_s.so.1`, `libstdc++.so.6` and the four shared abseil libraries. Each app image's own
layers contain only its own content. Counting distinct layer blobs referenced by the
manifests:

| Set | Naive per-image sum | Distinct blobs | Saved |
|---|---:|---:|---:|
| base + 3 library-only runtimes | 40,596,258 B | 32,352,027 B | 8,244,231 B (20.3%) |
| all five, incl. llama-cpp | 1,545,662,044 B | 1,534,669,736 B | 10,992,308 B (0.7%) |

The 0.7% figure is an artifact of scale, not a regression: llama-cpp's model layer is
1,493,674,438 B on its own, which swamps everything else. The base-sharing result is the
20.3% row — the shared runtime is still shared exactly as designed, and llama-cpp references
the same base blob as the other three.

Inside `meta-ai-llama-cpp-oci`: `usr/bin/llama-server` (8,579,136 B) is in layer
`10056fd2707b`, and `usr/share/llama-cpp/models/Qwen3.5-0.8B-BF16.gguf` (1,516,744,736 B) is
alone in layer `078f8301d6b1`.

The `qemuarm64` proxy image boots and the full import chain completes. `docker`, `containerd`
and all four `meta-ai-oci-import@<app>` instances report `active`. The llama-cpp container was
then actually run and exercised end-to-end:

```
docker run -d --name llama-test -p 8080:8080 meta-ai-llama-cpp:latest
```

```
main: model loaded
main: server is listening on http://0.0.0.0:8080
main: starting the main loop...
srv  update_slots: all slots are idle
```

`/proc/1/cmdline` in the running container confirms the entrypoint/cmd split works as
configured (`/usr/bin/catatonit -- /usr/bin/llama-server --host 0.0.0.0 --model ...
--ctx-size 16384 ...`), and `GET /` returns 6917 bytes of the real llama.cpp frontend:

```
<!--
  This is a static build of the frontend.
  It is automatically generated by the build process.
  Do not edit this file directly.
  To make changes, refer to the "Web UI" section in the README.
```

The same request returned `HTTP/1.1 404 Not Found` before `LLAMA_BUILD_WEBUI=ON`. Exact
figures and full observed output are in `reports/container-host-image-build-report.md`.

On-hardware `rb1-core-kit` boot is **not** verified — no board in this environment. The
`qcomflash` bundle is produced and structurally complete, but the physical flashing path, the
board's `linux-qcom-next` kernel config against docker/containerd's cgroup and namespace
requirements, and any board-specific device passthrough remain unproven.

## Known limitations

**Stale `latest` references survive the five-image import.** After all four apps import, two
bare `latest` rows remain in `docker images`, sharing an image ID with
`meta-ai-tensorflow-lite:latest`. The wrapper is supposed to drop the `latest:latest`
reference immediately after retagging by ID, and did so cleanly in the earlier three-app run,
so this is a regression introduced somewhere in the move to four apps. The four intended tags
are all present, correct and distinct, so the images are usable — but the import path is not
clean and the script needs another look.

**`libstdc++` cannot be named in `OCI_LAYERS`.** The `packages:` syntax joins package names
with a literal `+` and provides no escape mechanism for a `+` inside a package name, so
`libgcc+libstdc++` silently mis-splits into `libgcc` and `libstdc` — the trailing `++` is
swallowed, and the build proceeds with a package name that doesn't exist. This is a genuine
`image-oci.bbclass` limitation with no downstream workaround; the base recipe simply doesn't
name `libstdc++`. In this particular package set it lands in the shared base layer anyway,
transitively via abseil's `RDEPENDS`, so nothing is duplicated in practice — but that is luck,
not design, and a package set without that transitive path would lose the sharing. A proper
fix needs a delimiter change or escape syntax upstream.

**The OCI layer cache lies.** Bug 0004 above is a workaround-only situation: any change that
rebuilds a package without bumping `PKGV` will silently ship stale layer content until the
cache directory is deleted *and* `do_image_oci` is force-run. Anyone iterating on a recipe's
build flags on this branch will hit it.

**Floating layer refs.** All layers track `master` with no lockfile, matching `meta-ai`'s
existing convention. A `meta-virtualization` or oe-core regression will surface here first,
the four masked meta-virtualization recipes are a symptom of exactly that, and the local
patches will eventually stop applying.

**First boot is slow.** The serialized `docker load` of four images is dominated by
llama-cpp's ~1.4 GiB archive, which alone takes over a minute of wall time on the qemu proxy.
Correctness required serialization; if this matters, the fix is upstream in containerd's
handling of concurrent loads sharing content, not here.

**The primary image is large and untuned.** The container stack (docker, containerd, runc and
their Go runtimes) plus 4 GB of deliberate rootfs headroom plus ~1.5 GiB of baked-in model
dominate it. The `.ext4` and `qcomflash` sizes recorded in
`reports/container-host-image-build-report.md` predate llama-cpp and the headroom bump; they
have not been re-measured for the five-image build.

## Natural next steps

1. **Boot on real hardware.** Flash the `qcomflash` bundle to an `rb1-core-kit`, confirm
   dockerd starts against `linux-qcom-next`, and exercise the CDI generator with a real
   GPU/NPU passthrough into a container — ideally into the llama-cpp container, which is the
   one that could actually use an NPU. This is the only remaining gap between "working
   reference" and "working product".
2. **Fix the stale-`latest` regression** in `meta-ai-oci-import.sh`, and add a check that the
   number of `latest:latest` references after all imports is zero.
3. **Send patches 0003 and 0004 upstream** alongside the already-drafted 0001 and 0002, and
   file the `setscene_depvalid` `-initial` gate as an oe-core issue — the local class under
   `classes/` is a workaround for a bug that isn't in `meta-virtualization`.
4. **Fix the `OCI_LAYERS` `+` delimiter upstream** so `libstdc++` can be named explicitly and
   the base layer's contents stop depending on a transitive accident.
5. **Measure `--checksum`'s build-time cost.** It hashes every file's content instead of
   `stat()`-ing it. Almost certainly negligible at these layer sizes, but unmeasured, and it's
   the obvious first objection to patch 0003 upstream.
6. **Re-measure the primary image** for the five-image build, and trim it. Neither the 4 GB
   rootfs headroom nor the container stack's footprint has been examined; the headroom in
   particular was set to a round number that comfortably works, not a measured one.
