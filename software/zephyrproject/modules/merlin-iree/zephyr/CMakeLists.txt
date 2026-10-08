# SPDX-License-Identifier: Apache-2.0
#
# Zephyr module CMakeLists for the Merlin / IREE static runtime.
#
# IREE does not install its runtime static archives or headers (only the
# compiler shared library makes it into install/), so we consume the IREE
# build tree directly:
#
#   archives -> $MERLIN_BUILD_DIR/runtime/src/iree/.../libiree_*.a
#   headers  -> $MERLIN_ROOT/third_party/iree_bar/runtime/src
#
# `./merlin build --profile zephyr` populates
# $MERLIN_ROOT/build/zephyr-vanilla-release/ with both. The lookup paths
# below are honoured in the order:
#
#   1. -DMERLIN_BUILD_DIR=...      (CMake cache or command line)
#   2. -DMERLIN_INSTALL_DIR=...    (legacy / packaged install layout)
#   3. CONFIG_MERLIN_IREE_INSTALL_DIR  (Kconfig string)
#   4. $MERLIN_ROOT/build/zephyr-vanilla-release/{,install/}
#
# The module exposes a single zephyr_interface_library_named target named
# `merlin_iree`; Zephyr applications link against it via:
#
#   target_link_libraries(app PRIVATE merlin_iree)
#
# IREE itself is built under IREE_PLATFORM_GENERIC + IREE_ENABLE_THREADING=OFF
# + IREE_HAL_DRIVER_LOCAL_SYNC=ON. Anything that depends on
# pthreads/futex/synchronization is intentionally absent from the archive
# set below.

if(NOT CONFIG_MERLIN_IREE)
  return()
endif()

# --- 1. Resolve build / install directory --------------------------------

set(_merlin_root_default "")
if(DEFINED ENV{MERLIN_ROOT})
  set(_merlin_root_default "$ENV{MERLIN_ROOT}")
endif()

# Build tree (preferred -- this is what `merlin build --profile zephyr`
# actually populates).
if(NOT DEFINED MERLIN_BUILD_DIR)
  if(_merlin_root_default)
    set(MERLIN_BUILD_DIR
      "${_merlin_root_default}/build/zephyr-vanilla-release")
  endif()
endif()

# Install tree (legacy / future packaged layout).
if(NOT DEFINED MERLIN_INSTALL_DIR)
  if(CONFIG_MERLIN_IREE_INSTALL_DIR)
    set(MERLIN_INSTALL_DIR "${CONFIG_MERLIN_IREE_INSTALL_DIR}")
  elseif(_merlin_root_default)
    set(MERLIN_INSTALL_DIR
      "${_merlin_root_default}/build/zephyr-vanilla-release/install")
  endif()
endif()
string(REPLACE "$(MERLIN_ROOT)" "${_merlin_root_default}"
  MERLIN_INSTALL_DIR "${MERLIN_INSTALL_DIR}")

# Header source comes from iree_bar regardless of build/install layout.
if(NOT DEFINED MERLIN_IREE_HEADERS_DIR)
  if(_merlin_root_default)
    set(MERLIN_IREE_HEADERS_DIR
      "${_merlin_root_default}/third_party/iree_bar/runtime/src")
  endif()
endif()

# Sanity check.
if(NOT EXISTS "${MERLIN_IREE_HEADERS_DIR}/iree/base/api.h")
  message(FATAL_ERROR
    "merlin-iree: cannot locate IREE headers.\n"
    "  Tried: ${MERLIN_IREE_HEADERS_DIR}/iree/base/api.h\n"
    "  Set -DMERLIN_IREE_HEADERS_DIR=... or export MERLIN_ROOT.")
endif()

if(NOT EXISTS "${MERLIN_BUILD_DIR}" AND NOT EXISTS "${MERLIN_INSTALL_DIR}")
  message(FATAL_ERROR
    "merlin-iree: cannot locate IREE build artifacts.\n"
    "  Tried build dir:   ${MERLIN_BUILD_DIR}\n"
    "  Tried install dir: ${MERLIN_INSTALL_DIR}\n"
    "  Run: ./merlin build --profile zephyr --config release")
endif()

message(STATUS "merlin-iree: build dir   = ${MERLIN_BUILD_DIR}")
message(STATUS "merlin-iree: headers dir = ${MERLIN_IREE_HEADERS_DIR}")

# --- 2. Locate IREE static archives --------------------------------------
#
# Archive name set matches what `merlin build --profile zephyr` produces
# under IREE_PLATFORM_GENERIC + IREE_ENABLE_THREADING=OFF +
# IREE_HAL_DRIVER_LOCAL_SYNC=ON. Names cross-checked against
# build/firesim-merlin-release (same toolchain + flags). Paths are not
# fixed because IREE's CMake places archives under per-component
# subdirectories; we use file(GLOB_RECURSE) to find them.

set(_merlin_iree_libs
  # Public API
  iree_modules_hal_hal
  iree_modules_hal_types
  iree_modules_hal_debugging
  iree_modules_hal_utils_buffer_diagnostics
  # HAL: local-sync (inline) + local-task (multi-hart executor)
  iree_hal_drivers_local_sync_sync_driver
  iree_hal_drivers_local_task_task_driver
  iree_hal_drivers_local_task_registration_registration
  iree_task_task
  iree_task_api
  iree_hal_local_local
  iree_hal_local_loaders_embedded_elf_loader
  iree_hal_local_executable_loader
  iree_hal_local_executable_environment
  iree_hal_local_executable_library_util
  iree_hal_local_executable_format
  iree_hal_local_elf_elf_module
  iree_hal_local_elf_arch
  iree_hal_local_elf_platform
  iree_hal_local_plugins_registration_registration
  iree_hal_local_executable_plugin_manager
  iree_hal_hal
  # HAL utils (deferred command buffer, queue emulation, file IO,
  # platform topology, file transfer / file cache / generic file ops)
  iree_hal_utils_deferred_command_buffer
  iree_hal_utils_queue_emulation
  iree_hal_utils_file_cache
  iree_hal_utils_file_transfer
  iree_hal_utils_files
  iree_hal_utils_platform_topology
  iree_hal_utils_resource_set
  # iree_async (semaphore + frontier tracker + proactor) + platform + util
  iree_async_async
  iree_async_platform
  iree_async_util_proactor_pool
  iree_async_util_proactor_thread
  iree_async_util_proactor_thread_runner
  # iree_io for file_handle wrapping
  iree_io_file_handle
  # threading: iree_slim_mutex_* (futex-backed) + iree_strncpy_s helper
  iree_base_threading_threading
  iree_base_threading_thread
  # VM
  iree_vm_impl
  iree_vm_bytecode_module
  iree_vm_bytecode_utils_utils
  # Base / internal primitives
  iree_base_internal_arena
  iree_base_internal_atomic_slist
  iree_base_internal_cpu
  iree_base_internal_fpu_state
  iree_base_internal_memory
  iree_base_internal_path
  iree_base_internal_time
  iree_base_base
  # flatcc verifier (used by VM bytecode verification)
  flatcc_parsing
)
# Optional archives -- present when a build profile pulls them in (host
# tooling, etc.) but absent from the curated `zephyr` profile and not
# required by the local-sync HAL closure.
set(_merlin_iree_optional_libs
  iree_base_tooling_flags
  iree_base_internal_dynamic_library
  iree_base_internal_unicode
)
list(APPEND _merlin_iree_libs ${_merlin_iree_optional_libs})

# Build a name -> archive-path map by globbing the build tree once.
# `runtime/...` covers iree_*; `build_tools/third_party/...` picks up
# flatcc_parsing.a (used by VM bytecode verification).
file(GLOB_RECURSE _all_archives
  "${MERLIN_BUILD_DIR}/runtime/*.a"
  "${MERLIN_BUILD_DIR}/build_tools/third_party/*.a"
  "${MERLIN_INSTALL_DIR}/lib/*.a"
)

set(_resolved_libs)
foreach(_lib IN LISTS _merlin_iree_libs)
  set(_archive "")
  foreach(_path IN LISTS _all_archives)
    get_filename_component(_base "${_path}" NAME_WE)
    if("${_base}" STREQUAL "lib${_lib}")
      set(_archive "${_path}")
      break()
    endif()
  endforeach()
  if(_archive STREQUAL "")
    list(FIND _merlin_iree_optional_libs "${_lib}" _is_optional)
    if(_is_optional EQUAL -1)
      message(WARNING
        "merlin-iree: archive not found: ${_lib} (skipping). "
        "Did `./merlin build --profile zephyr` succeed?")
    endif()
    continue()
  endif()
  add_library(${_lib} STATIC IMPORTED GLOBAL)
  set_target_properties(${_lib} PROPERTIES
    IMPORTED_LOCATION "${_archive}")
  list(APPEND _resolved_libs ${_lib})
endforeach()

# --- 3. Expose as a Zephyr interface library ------------------------------

zephyr_interface_library_named(merlin_iree)

target_include_directories(merlin_iree INTERFACE
  "${MERLIN_IREE_HEADERS_DIR}"
)

# Stub-header overlay: a <memory.h> shim so picolibc-based Zephyr apps
# can satisfy iree's `#include <memory.h>` (a deprecated newlib/glibc
# alias for <string.h> that picolibc does not ship). The same overlay
# directory carries the BSD-sockets stubs the IREE build itself uses.
if(DEFINED ENV{MERLIN_ROOT})
  set(_merlin_zephyr_stubs "$ENV{MERLIN_ROOT}/build_tools/firesim/zephyr_stubs")
  if(EXISTS "${_merlin_zephyr_stubs}/memory.h")
    target_include_directories(merlin_iree SYSTEM INTERFACE
      "${_merlin_zephyr_stubs}"
    )
  endif()
endif()

# Compile defines that match how the IREE archives were built. Keeping
# these in sync with build_tools/firesim/riscv_firesim.toolchain.cmake
# (zephyr profile branch) is critical -- a mismatch produces silent ABI
# breakage.
target_compile_definitions(merlin_iree INTERFACE
  # NOTE: do NOT set IREE_PLATFORM_GENERIC here. The Zephyr app and the
  # iree_thread_zephyr.c / iree_futex_zephyr.c bridge files need
  # IREE_PLATFORM_ZEPHYR to be auto-detected from __ZEPHYR__ (which Zephyr
  # always defines). Setting GENERIC suppresses that detection and the
  # bridge files compile to empty TUs, leaving iree_thread_* / iree_futex_*
  # undefined at link.
  IREE_MEMORY_ACCESS_ALIGNMENT_REQUIRED=1
  IREE_FILE_IO_ENABLE=0
  IREE_HAL_MODULE_LOAD_FROM_FILE_DISABLE=1
  IREE_HAL_DRIVER_LOCAL_SYNC=1
  IREE_HAL_DRIVER_LOCAL_TASK=1
  # NOTE: we deliberately do *not* set IREE_SYNCHRONIZATION_DISABLE_UNSAFE
  # on the consumer side, while the IREE archives *are* built with it. The
  # asymmetry is intentional:
  #   - With it: atomics_disabled.h's `_Generic` lacks an intptr arm and
  #     fails on inline calls in iree/async/socket.h.
  #   - Without it: iree/base/threading/{mutex,notification}.h include
  #     <pthread.h> and embed pthread_mutex_t fields. Picolibc has no
  #     pthread.h.
  #
  # Resolution: drop the define on the consumer (so atomics.h selects
  # atomics_gcc.h, which uses typeless __atomic_* builtins) AND provide
  # a stub <pthread.h> via the zephyr_stubs overlay. The stub's opaque
  # struct sizes only matter for consumer compilation -- IREE archives
  # built with IREE_SYNCHRONIZATION_DISABLE_UNSAFE never instantiate
  # pthread types and never see the stub. Inline functions that touch
  # pthread fields are never *called* from our local-sync code path, so
  # --gc-sections drops all unresolved pthread_* references at link time.
  #
  # Match the toolchain.cmake bare-metal/zephyr profile so iree's
  # `iree_allocator_system()` macro expands to a valid declaration in
  # consumer translation units (not just inside the IREE archives).
  IREE_ALLOCATOR_SYSTEM_CTL=iree_allocator_libc_ctl
  IREE_DEVICE_SIZE_T=uint64_t
  IREE_WAIT_UNTIL_FN=sizeof
  # IREE headers use C11 `static_assert(expr, msg)` keyword form. Some
  # libc setups (picolibc + cross-compile gcc here) don't define it via
  # <assert.h> in the include order Zephyr drives, so map it directly to
  # the C11 builtin keyword.
  static_assert=_Static_assert
)

# --start-group / --end-group groups the static archives so the linker can
# resolve mutual references between iree_hal_local_local,
# iree_hal_drivers_local_sync_*, iree_vm_impl, etc.
if(_resolved_libs)
  target_link_libraries(merlin_iree INTERFACE
    -Wl,--start-group
    ${_resolved_libs}
    -Wl,--end-group
    m  # libm: IREE uses sqrtf, expf, etc. in fp paths.
  )
endif()

# Newlib's malloc on Zephyr provides memalign via <malloc.h>; IREE's
# bare-metal samples use it for 128-byte alignment of input buffers.
zephyr_compile_definitions_ifdef(CONFIG_NEWLIB_LIBC _GNU_SOURCE)
