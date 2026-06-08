/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2022 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#ifndef XENIA_GPU_VULKAN_VULKAN_PIPELINE_STATE_CACHE_H_
#define XENIA_GPU_VULKAN_VULKAN_PIPELINE_STATE_CACHE_H_

#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <deque>
#include <filesystem>
#include <functional>
#include <memory>
#include <unordered_map>
#include <utility>
#include <vector>

#include "xenia/base/hash.h"
#include "xenia/base/mutex.h"
#include "xenia/base/platform.h"
#include "xenia/base/threading.h"
#include "xenia/base/xxhash.h"
#include "xenia/gpu/primitive_processor.h"
#include "xenia/gpu/register_file.h"
#include "xenia/gpu/registers.h"
#include "xenia/gpu/spirv_shader_translator.h"
#include "xenia/gpu/vulkan/vulkan_render_target_cache.h"
#include "xenia/gpu/vulkan/vulkan_shader.h"
#include "xenia/gpu/xenos.h"
#include "xenia/ui/vulkan/vulkan_api.h"

namespace xe {
namespace gpu {
namespace vulkan {

class VulkanCommandProcessor;

// TODO(Triang3l): Create a common base for both the Vulkan and the Direct3D
// implementations.
class VulkanPipelineCache {
 public:
  static constexpr size_t kLayoutUIDEmpty = 0;

  class PipelineLayoutProvider {
   public:
    virtual ~PipelineLayoutProvider() {}
    virtual VkPipelineLayout GetPipelineLayout() const = 0;

   protected:
    PipelineLayoutProvider() = default;
  };

  VulkanPipelineCache(VulkanCommandProcessor& command_processor,
                      const RegisterFile& register_file,
                      VulkanRenderTargetCache& render_target_cache,
                      VkShaderStageFlags guest_shader_vertex_stages);
  ~VulkanPipelineCache();

  bool Initialize();
  void Shutdown();

  // Seeds the persistent host VkPipelineCache from disk for this title and
  // records where to write it back on shutdown. Safe to call after Initialize.
  void InitializeShaderStorage(const std::filesystem::path& cache_root,
                               uint32_t title_id);

  // Persistent host pipeline cache handle (VK_NULL_HANDLE if unavailable);
  // pass to vkCreate*Pipelines. Purely an optimization.
  VkPipelineCache GetDevicePipelineCache() const {
    return device_pipeline_cache_;
  }

  // Serializes device_pipeline_cache_ to device_pipeline_cache_path_ (atomic
  // tmp+rename). MUST run on the command-processor (GPU) thread
  // (vkGetPipelineCacheData is externally synchronized). No-op if cache/path
  // unavailable, or if !force and nothing new since the last save. Returns true
  // if a write happened.
  bool SerializeDevicePipelineCache(bool force = false);
  // Throttled per-frame entry: serializes at most once per ~20s and only when
  // new pipelines exist. Call from the GPU thread at a frame boundary.
  void SerializeDevicePipelineCacheIfDue();

  VulkanShader* LoadShader(xenos::ShaderType shader_type,
                           const uint32_t* host_address, uint32_t dword_count);
  // Analyze shader microcode on the translator thread.
  void AnalyzeShaderUcode(Shader& shader) {
    shader.AnalyzeUcode(ucode_disasm_buffer_);
  }

  // Retrieves the shader modification for the current state. The shader must
  // have microcode analyzed.
  SpirvShaderTranslator::Modification GetCurrentVertexShaderModification(
      const Shader& shader,
      Shader::HostVertexShaderType host_vertex_shader_type,
      uint32_t interpolator_mask, bool ps_param_gen_used) const;
  SpirvShaderTranslator::Modification GetCurrentPixelShaderModification(
      const Shader& shader, uint32_t interpolator_mask,
      uint32_t param_gen_pos) const;

  bool EnsureShadersTranslated(VulkanShader::VulkanTranslation* vertex_shader,
                               VulkanShader::VulkanTranslation* pixel_shader);
  // Deferred-creation aware. pipeline_out receives a STABLE pointer into the
  // pipelines_ node's VkPipeline slot (unordered_map nodes are address-stable),
  // NOT a handle value. When asynchronous creation threads are active the slot
  // may still be VK_NULL_HANDLE on return; it is guaranteed non-null after
  // AwaitCreationCompletion() runs at the submission boundary. With
  // vulkan_pipeline_creation_threads == 0 (the default) creation runs inline and
  // the slot is already filled on return.
  bool ConfigurePipeline(
      VulkanShader::VulkanTranslation* vertex_shader,
      VulkanShader::VulkanTranslation* pixel_shader,
      const PrimitiveProcessor::ProcessingResult& primitive_processing_result,
      reg::RB_DEPTHCONTROL normalized_depth_control,
      uint32_t normalized_color_mask,
      VulkanRenderTargetCache::RenderPassKey render_pass_key,
      const VkPipeline*& pipeline_out,
      const PipelineLayoutProvider*& pipeline_layout_out);

  // Submission-boundary block (ported from D3D12 PipelineCache::EndSubmission).
  // Must be called on the command-processor (GPU) thread immediately before
  // replaying the deferred command buffer. Drains any remaining queued
  // pipelines on this thread, then waits until no creation thread is busy, so
  // every deferred pipeline pointer in the stream resolves to a non-null handle.
  // No-op when no creation threads are active.
  void AwaitCreationCompletion();

 private:
  enum class PipelineGeometryShader : uint32_t {
    kNone,
    kPointList,
    kRectangleList,
    kQuadList,
  };

  enum class PipelinePrimitiveTopology : uint32_t {
    kPointList,
    kLineList,
    kLineStrip,
    kTriangleList,
    kTriangleStrip,
    kTriangleFan,
    kLineListWithAdjacency,
    kPatchList,
  };

  enum class PipelinePolygonMode : uint32_t {
    kFill,
    kLine,
    kPoint,
  };

  enum class PipelineBlendFactor : uint32_t {
    kZero,
    kOne,
    kSrcColor,
    kOneMinusSrcColor,
    kDstColor,
    kOneMinusDstColor,
    kSrcAlpha,
    kOneMinusSrcAlpha,
    kDstAlpha,
    kOneMinusDstAlpha,
    kConstantColor,
    kOneMinusConstantColor,
    kConstantAlpha,
    kOneMinusConstantAlpha,
    kSrcAlphaSaturate,
  };

  // Update PipelineDescription::kVersion if anything is changed!
  XEPACKEDSTRUCT(PipelineRenderTarget, {
    PipelineBlendFactor src_color_blend_factor : 4;  // 4
    PipelineBlendFactor dst_color_blend_factor : 4;  // 8
    xenos::BlendOp color_blend_op : 3;               // 11
    PipelineBlendFactor src_alpha_blend_factor : 4;  // 15
    PipelineBlendFactor dst_alpha_blend_factor : 4;  // 19
    xenos::BlendOp alpha_blend_op : 3;               // 22
    uint32_t color_write_mask : 4;                   // 26
  });

  XEPACKEDSTRUCT(PipelineDescription, {
    uint64_t vertex_shader_hash;
    uint64_t vertex_shader_modification;
    // 0 if no pixel shader.
    uint64_t pixel_shader_hash;
    uint64_t pixel_shader_modification;
    VulkanRenderTargetCache::RenderPassKey render_pass_key;

    // Shader stages.
    PipelineGeometryShader geometry_shader : 2;  // 2
    // Input assembly.
    PipelinePrimitiveTopology primitive_topology : 3;  // 5
    uint32_t primitive_restart : 1;                    // 6
    // Rasterization.
    uint32_t depth_clamp_enable : 1;       // 7
    PipelinePolygonMode polygon_mode : 2;  // 9
    uint32_t cull_front : 1;               // 10
    uint32_t cull_back : 1;                // 11
    uint32_t front_face_clockwise : 1;     // 12
    // Depth / stencil.
    uint32_t depth_write_enable : 1;                      // 13
    xenos::CompareFunction depth_compare_op : 3;          // 15
    uint32_t stencil_test_enable : 1;                     // 17
    xenos::StencilOp stencil_front_fail_op : 3;           // 20
    xenos::StencilOp stencil_front_pass_op : 3;           // 23
    xenos::StencilOp stencil_front_depth_fail_op : 3;     // 26
    xenos::CompareFunction stencil_front_compare_op : 3;  // 29
    xenos::StencilOp stencil_back_fail_op : 3;            // 32

    xenos::StencilOp stencil_back_pass_op : 3;           // 3
    xenos::StencilOp stencil_back_depth_fail_op : 3;     // 6
    xenos::CompareFunction stencil_back_compare_op : 3;  // 9

    // Filled only for the attachments present in the render pass object.
    PipelineRenderTarget render_targets[xenos::kMaxColorRenderTargets];

    // Including all the padding, for a stable hash.
    PipelineDescription() { Reset(); }
    PipelineDescription(const PipelineDescription& description) {
      std::memcpy(this, &description, sizeof(*this));
    }
    PipelineDescription& operator=(const PipelineDescription& description) {
      std::memcpy(this, &description, sizeof(*this));
      return *this;
    }
    bool operator==(const PipelineDescription& description) const {
      return std::memcmp(this, &description, sizeof(*this)) == 0;
    }
    void Reset() { std::memset(this, 0, sizeof(*this)); }
    uint64_t GetHash() const { return XXH3_64bits(this, sizeof(*this)); }
    struct Hasher {
      size_t operator()(const PipelineDescription& description) const {
        return size_t(description.GetHash());
      }
    };
  });

  struct Pipeline {
    VkPipeline pipeline = VK_NULL_HANDLE;
    // The layouts are owned by the VulkanCommandProcessor, and must not be
    // destroyed by it while the pipeline cache is active.
    const PipelineLayoutProvider* pipeline_layout;
    Pipeline(const PipelineLayoutProvider* pipeline_layout_provider)
        : pipeline_layout(pipeline_layout_provider) {}
  };

  // Description that can be passed from the command processor thread to the
  // creation threads, with everything needed from caches pre-looked-up.
  struct PipelineCreationArguments {
    std::pair<const PipelineDescription, Pipeline>* pipeline;
    const VulkanShader::VulkanTranslation* vertex_shader;
    const VulkanShader::VulkanTranslation* pixel_shader;
    VkShaderModule geometry_shader;
    VkRenderPass render_pass;
  };

  union GeometryShaderKey {
    uint32_t key;
    struct {
      PipelineGeometryShader type : 2;
      uint32_t interpolator_count : 5;
      uint32_t user_clip_plane_count : 3;
      uint32_t user_clip_plane_cull : 1;
      uint32_t has_vertex_kill_and : 1;
      uint32_t has_point_size : 1;
      uint32_t has_point_coordinates : 1;
    };

    GeometryShaderKey() : key(0) { static_assert_size(*this, sizeof(key)); }

    struct Hasher {
      size_t operator()(const GeometryShaderKey& key) const {
        return std::hash<uint32_t>{}(key.key);
      }
    };
    bool operator==(const GeometryShaderKey& other_key) const {
      return key == other_key.key;
    }
    bool operator!=(const GeometryShaderKey& other_key) const {
      return !(*this == other_key);
    }
  };

  // Can be called from multiple threads.
  bool TranslateAnalyzedShader(SpirvShaderTranslator& translator,
                               VulkanShader::VulkanTranslation& translation);

  void WritePipelineRenderTargetDescription(
      reg::RB_BLENDCONTROL blend_control, uint32_t write_mask,
      PipelineRenderTarget& render_target_out) const;
  bool GetCurrentStateDescription(
      const VulkanShader::VulkanTranslation* vertex_shader,
      const VulkanShader::VulkanTranslation* pixel_shader,
      const PrimitiveProcessor::ProcessingResult& primitive_processing_result,
      reg::RB_DEPTHCONTROL normalized_depth_control,
      uint32_t normalized_color_mask,
      VulkanRenderTargetCache::RenderPassKey render_pass_key,
      PipelineDescription& description_out) const;

  // Whether the pipeline for the given description is supported by the device.
  bool ArePipelineRequirementsMet(const PipelineDescription& description) const;

  static bool GetGeometryShaderKey(
      PipelineGeometryShader geometry_shader_type,
      SpirvShaderTranslator::Modification vertex_shader_modification,
      SpirvShaderTranslator::Modification pixel_shader_modification,
      GeometryShaderKey& key_out);
  VkShaderModule GetGeometryShader(GeometryShaderKey key);

  // Can be called from creation threads - all needed data must be fully set up
  // at the point of the call: shaders must be translated, pipeline layout and
  // render pass objects must be available. The pipeline is compiled into
  // target_cache: device_pipeline_cache_ for the synchronous / GPU-thread drain
  // path, or a per-thread VkPipelineCache for asynchronous creation threads (the
  // result is merged into device_pipeline_cache_ under
  // device_pipeline_cache_mutex_).
  bool EnsurePipelineCreated(
      const PipelineCreationArguments& creation_arguments,
      VkPipelineCache target_cache);

  // Pipeline creation threads (ported from D3D12 PipelineCache). Each worker
  // owns a private VkPipelineCache and merges into device_pipeline_cache_.
  void CreationThread(size_t thread_index);
  // Drains the creation queue on the calling (GPU) thread, compiling directly
  // into device_pipeline_cache_.
  void CreateQueuedPipelinesOnProcessorThread();

  // Builds cache_root/"pipeline_cache_vulkan"/"{title_id:08X}.vk_pso_cache".
  static std::filesystem::path GetDevicePipelineCachePath(
      const std::filesystem::path& cache_root, uint32_t title_id);

  VulkanCommandProcessor& command_processor_;
  const RegisterFile& register_file_;
  VulkanRenderTargetCache& render_target_cache_;
  VkShaderStageFlags guest_shader_vertex_stages_;

  // Temporary storage for AnalyzeUcode calls on the processor thread.
  StringBuffer ucode_disasm_buffer_;
  // Reusable shader translator on the command processor thread.
  std::unique_ptr<SpirvShaderTranslator> shader_translator_;

  struct LayoutUID {
    size_t uid;
    size_t vector_span_offset;
    size_t vector_span_length;
  };
  std::mutex layouts_mutex_;
  // Texture binding layouts of different shaders, for obtaining layout UIDs.
  std::vector<VulkanShader::TextureBinding> texture_binding_layouts_;
  // Map of texture binding layouts used by shaders, for obtaining UIDs. Keys
  // are XXH3 hashes of layouts, values need manual collision resolution using
  // layout_vector_offset:layout_length of texture_binding_layouts_.
  std::unordered_multimap<uint64_t, LayoutUID,
                          xe::hash::IdentityHasher<uint64_t>>
      texture_binding_layout_map_;

  // Ucode hash -> shader.
  std::unordered_map<uint64_t, VulkanShader*,
                     xe::hash::IdentityHasher<uint64_t>>
      shaders_;

  // Geometry shaders for Xenos primitive types not supported by Vulkan.
  // Stores VK_NULL_HANDLE if failed to create.
  std::unordered_map<GeometryShaderKey, VkShaderModule,
                     GeometryShaderKey::Hasher>
      geometry_shaders_;

  // Empty depth-only pixel shader for writing to depth buffer using fragment
  // shader interlock when no Xenos pixel shader provided.
  VkShaderModule depth_only_fragment_shader_ = VK_NULL_HANDLE;

  // LIFETIME: std::unordered_map never relocates its nodes on insert/rehash
  // ([unord.req]), so &node.second.pipeline is a stable pointer for the node's
  // lifetime. The deferred command buffer records that stable pointer and
  // dereferences it at replay; this is only safe because the cache never erases
  // entries while running (it is cleared only in Shutdown(), AFTER all creation
  // threads have been joined). Do NOT add pipelines_.erase() or switch to a
  // relocating container without revisiting the deferred-bind indirection.
  std::unordered_map<PipelineDescription, Pipeline, PipelineDescription::Hasher>
      pipelines_;

  // Previously used pipeline, to avoid lookups if the state wasn't changed.
  const std::pair<const PipelineDescription, Pipeline>* last_pipeline_ =
      nullptr;

  // Persistent host pipeline cache, seeded from disk in InitializeShaderStorage
  // and written back in Shutdown. VK_NULL_HANDLE if creation failed (treated as
  // "no cache" by Vulkan).
  VkPipelineCache device_pipeline_cache_ = VK_NULL_HANDLE;
  // Guards ALL host access to device_pipeline_cache_ that Vulkan requires to be
  // externally synchronized: vkGetPipelineCacheData (serialize, GPU thread),
  // vkMergePipelineCaches (seed at startup on the GPU thread; per-thread merges
  // from creation threads), and the GPU-thread drain create. Also guards
  // pipelines_created_count_ writes/reads (now multi-writer: creation threads
  // bump it under this lock; the GPU thread reads it under this lock at
  // serialize time). With vulkan_pipeline_creation_threads == 0 this mutex is
  // only ever taken on the GPU thread and is uncontended.
  xe_mutex device_pipeline_cache_mutex_;
  // Where to write the cache blob on shutdown; empty until
  // InitializeShaderStorage.
  std::filesystem::path device_pipeline_cache_path_;

  // Bumped after each successful vkCreateGraphicsPipelines, under
  // device_pipeline_cache_mutex_ (multi-writer: creation threads). Compared to
  // pipelines_serialized_count_ to detect new work; read at serialize time on
  // the GPU thread under the same mutex.
  uint64_t pipelines_created_count_ = 0;
  uint64_t pipelines_serialized_count_ = 0;
  // Host-uptime ms of the last successful serialize, for debounce throttling.
  uint64_t last_serialize_ms_ = 0;

  // Pipeline creation threads (ported 1:1 from d3d12/pipeline_cache.h:403-426).
  // STRICT LOCK ORDERING: a creation thread NEVER holds creation_request_lock_
  // and device_pipeline_cache_mutex_ at the same time. It releases
  // creation_request_lock_ after dequeue (++busy under that lock), runs
  // EnsurePipelineCreated (which takes device_pipeline_cache_mutex_ only for the
  // merge), then re-takes creation_request_lock_ for --busy.
  // AwaitCreationCompletion holds only creation_request_lock_.
  xe_mutex creation_request_lock_;
  std::condition_variable_any creation_request_cond_;
  // Protected with creation_request_lock_, notify_one creation_request_cond_
  // when pushed. Stores stable node pointers; the per-node creation arguments
  // are owned separately in creation_queue_arguments_ (Vulkan's args hold raw
  // pointers, unlike D3D12 whose args are by-value in the Pipeline).
  std::deque<std::pair<const PipelineDescription, Pipeline>*> creation_queue_;
  // Owned creation arguments, 1:1 with creation_queue_ (same index order). The
  // referenced shaders / geometry shader / render pass are stable for the cache
  // lifetime; only this containing struct needs owned storage, because the
  // ConfigurePipeline stack local that produced it is gone by the time a worker
  // runs.
  std::deque<PipelineCreationArguments> creation_queue_arguments_;
  // Number of threads currently creating a pipeline - incremented when a
  // pipeline is dequeued (the completion event can't be triggered before this
  // is zero). Protected with creation_request_lock_.
  size_t creation_threads_busy_ = 0;
  // Manual-reset event set when the last queued pipeline is created and there
  // are no more pipelines to create.
  std::unique_ptr<xe::threading::Event> creation_completion_event_;
  // Whether setting the event on completion is queued. Protected with
  // creation_request_lock_, notify_one creation_request_cond_ when set.
  bool creation_completion_set_event_ = false;
  // Creation threads with this index or above need to shut down as soon as
  // possible. Protected with creation_request_lock_, notify_all
  // creation_request_cond_ when set.
  size_t creation_threads_shutdown_from_ = SIZE_MAX;
  std::vector<std::unique_ptr<xe::threading::Thread>> creation_threads_;
  // Per-thread VkPipelineCache (1:1 with creation_threads_), lazily created on a
  // worker's first compile, VK_NULL_HANDLE until then. Touched only by the
  // owning worker and by Shutdown() after the worker is joined - no other
  // synchronization needed.
  std::vector<VkPipelineCache> creation_thread_caches_;
};

}  // namespace vulkan
}  // namespace gpu
}  // namespace xe

#endif  // XENIA_GPU_VULKAN_VULKAN_PIPELINE_STATE_CACHE_H_
