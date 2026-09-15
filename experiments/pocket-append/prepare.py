#!/usr/bin/env python3
"""Patch ONLY a disposable host build prepared with scripts/build_sherpa.py.
Never used by Android/JNI or the live voice path.
"""
import argparse
from pathlib import Path
import hashlib

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('host_build',type=Path)
a=p.parse_args()
root=Path(__file__).resolve().parents[2]
expected='917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e'+hashlib.sha256((root/'native/sherpa/pocket-streaming.patch').read_bytes()).hexdigest()
assert (a.host_build/'source-version').read_text()==expected, 'Wrong native source/patch'
assert (a.host_build/'ort-host').is_dir() and not (a.host_build/'ort-android').exists(), 'Host builds only'
source=a.host_build/'source'
assert not (source/'.git').exists(), 'Use disposable extracted source'
header=source/'sherpa-onnx/csrc/offline-tts-pocket-impl.h'
s=header.read_text()
anchor='''      for (int step = 0; step < max_frames && running; ++step) {
        auto input'''
replacement='''      // HOST EXPERIMENT ONLY: keep the existing LM cache and acoustic state.
      // No BOS insertion, LM reset, decoder reset, or RNG reset on append.
      const int append_step = config.GetExtraInt("experiment_append_step", -1);
      const auto suffix = config.GetExtraString("experiment_append_text");
      bool appended = false;
      const int word_interval = config.GetExtraInt("experiment_word_interval", 0);
      std::vector<std::string> additions;
      if (word_interval > 0) {
        std::istringstream words(suffix);
        std::string word;
        while (words >> word) additions.push_back(" " + word);
      } else if (!suffix.empty()) additions.push_back(suffix);
      size_t added = 0;
      for (int step = 0; step < max_frames && running; ++step) {
        if (added < additions.size() && step == append_step + static_cast<int>(added) * word_interval) {
          auto embedding = GetTextEmbedding(additions[added++]);
          RunLmMain(View(&empty), std::move(embedding), lm);
          appended = true;
          fprintf(stderr, "EXPERIMENT appended_at_latent=%d prior_eos=%d\\n", step, eos);
        }
        auto input'''
if 'HOST EXPERIMENT ONLY' not in s:
 assert s.count(anchor)==1
 s=s.replace(anchor,replacement)
 anchor2='''      decode(1.0f);
      // Stop/restart'''
 assert s.count(anchor2)==1
 s=s.replace(anchor2,'''      fprintf(stderr, "EXPERIMENT finished append_requested=%d appended=%d eos=%d frames=%lld additions=%zu/%zu\\n",
          append_step, appended, eos, static_cast<long long>(state.frames), added, additions.size());
      decode(1.0f);
      // Stop/restart''')
 header.write_text(s)
else:
 assert replacement in s, 'Different experimental patch already installed; use a fresh host directory'
cmake=source/'CMakeLists.txt'
addition=f'''\nadd_executable(jarvis-pocket-append-check "{root}/experiments/pocket-append/check.cpp")
target_include_directories(jarvis-pocket-append-check PRIVATE "${{PROJECT_SOURCE_DIR}}")
target_link_libraries(jarvis-pocket-append-check sherpa-onnx-c-api)
'''
s=cmake.read_text()
if addition not in s: cmake.write_text(s+addition)
print('Prepared isolated append experiment. Reconfigure CMake and build jarvis-pocket-append-check.')
