# Galaxy Z Fold 6 local model benchmarks

These results were supplied from the target Galaxy Z Fold 6 using app version
1.0.16 and three runs per model.

| Model | Accelerator | Prefill | Decode | Time to first token | Initialization |
|---|---|---:|---:|---:|---:|
| Gemma-4-E2B-it | GPU | 516.1 tok/s | 31.7 tok/s | 0.529 s | 16.3 s first / 20.4 s steady |
| MobileActions-270M | CPU | 809.9 tok/s | 75.7 tok/s | 0.436 s | 2.1 s first / 0.27 s steady |

## Architecture decision

Gemma 4 E2B is the primary local intelligence model. MobileActions-270M is
the dedicated low-latency action router beneath it. Their separate runtime
boundaries allow each model to use the accelerator and lifecycle that fit its
job.

These measurements prove model feasibility on the target phone; the complete
assistant loop still requires end-to-end validation once the runtime adapters
are added.