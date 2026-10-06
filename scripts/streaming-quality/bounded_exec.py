"""Fixed one-process supervisor. No model data are placed in its receipts."""
import json
import os
from pathlib import Path
import resource
import signal
import subprocess
import time

GIB = 1024**3
MIB = 1024**2
BUDGET = dict(wall_seconds=240, cpu_seconds=240, address_space_bytes=4*GIB,
              rss_watchdog_bytes=3584*MIB, minimum_mem_available_bytes=5*GIB,
              system_reserve_bytes=GIB, cpu_threads=1, cpu_soft_limit_seconds=239, maximum_regular_output_file_bytes=128*MIB)


def available():
    return int(next(x for x in Path('/proc/meminfo').read_text().splitlines()
                    if x.startswith('MemAvailable:')).split()[1])*1024


def limits():
    resource.setrlimit(resource.RLIMIT_AS, (4*GIB, 4*GIB))
    resource.setrlimit(resource.RLIMIT_CPU, (239, 240))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_FSIZE, (128*MIB, 128*MIB))
    # Scheduling affinity also bounds native libraries that ignore thread hints.
    os.sched_setaffinity(0, {min(os.sched_getaffinity(0))})


def run(command, directory):
    if os.environ.get('GEMMA_QUALITY_COMPUTE_SLOT') != 'confirmed_by_owner':
        raise RuntimeError('A coordinated, post-compilation compute slot is required')
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=False)
    report = {'budget': BUDGET, 'execution_started': False,
              'mem_available_before_bytes': available(), 'rss_limit_is_sampled': True}
    receipt = directory/'process.json'
    def save():
        receipt.write_text(json.dumps(report, indent=2)+'\n')
        return report
    if report['mem_available_before_bytes'] < 5*GIB:
        report.update(status='resource_blocked', stop_reason='minimum_memory_headroom')
        return save()
    env = dict(os.environ, OMP_NUM_THREADS='1', OPENBLAS_NUM_THREADS='1',
               MKL_NUM_THREADS='1', MALLOC_ARENA_MAX='2')
    for k in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'): env.pop(k, None)
    start = time.monotonic()
    reason = None
    peak_rss = peak_virtual = 0
    with (directory/'stdout.log').open('wb') as out, (directory/'stderr.log').open('wb') as err:
        child = subprocess.Popen(list(map(str, command)), stdout=out, stderr=err,
            stdin=subprocess.DEVNULL, env=env, start_new_session=True, preexec_fn=limits)
        report['execution_started'] = True
        try:
            while child.poll() is None:
                memory = {}
                try:
                    for row in Path(f'/proc/{child.pid}/status').read_text().splitlines():
                        p = row.split()
                        if p and p[0] in ('VmRSS:', 'VmSize:'): memory[p[0]] = int(p[1])*1024
                except FileNotFoundError: break
                peak_rss = max(peak_rss, memory.get('VmRSS:', 0))
                peak_virtual = max(peak_virtual, memory.get('VmSize:', 0))
                if time.monotonic()-start >= 240: reason = 'wall_timeout'
                elif memory.get('VmRSS:', 0) > 3584*MIB: reason = 'rss_watchdog_limit'
                elif available() < GIB: reason = 'system_memory_reserve'
                if reason:
                    os.killpg(child.pid, signal.SIGKILL)
                    break
                time.sleep(.05)
            code = child.wait(timeout=5)
        finally:
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
    report.update(exit_code=code, wall_seconds=time.monotonic()-start,
                  sampled_peak_rss_bytes=peak_rss, sampled_peak_address_bytes=peak_virtual,
                  stop_reason=reason, status='completed' if code == 0 and reason is None else 'failed')
    return save()
