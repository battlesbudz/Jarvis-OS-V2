#!/usr/bin/env python3
"""Read link/archive response files and depfiles; never runs the probe or compiler."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
from replay_artifact_inventory import describe, parse_listing, SYSTEM_NAMES


def source_record(name, execroot, sdk):
    """Normalize only verified source roots; physical locations stay private."""
    path = Path(name)
    if '..' in path.parts or '\\' in str(path):
        raise ValueError('Source input escapes reviewed roots')
    execroot, sdk = Path(execroot).resolve(strict=True), Path(sdk).resolve(strict=True)
    physical = (path if path.is_absolute() else execroot/path).resolve(strict=True)
    # Bazel's execroot/external is a directory of per-repository symlinks,
    # not necessarily a symlink itself. Use the same canonical repository root
    # that hosted_capture verifies against the reviewed declaration policy.
    external = (execroot.parent.parent/'external').resolve()
    def external_source(origin):
        try:
            expected = (external/origin).resolve(strict=True)
            alias = (execroot/'external'/origin).resolve(strict=True)
        except OSError:
            raise ValueError('External source repository alias is unavailable') from None
        if not expected.is_relative_to(external) or alias != expected or not physical.is_relative_to(expected):
            raise ValueError('External source repository alias escaped its verified root')
        return 'external/'+origin+'/'+str(physical.relative_to(expected))
    if not path.is_absolute() and path.parts[:1] == ('external',):
        if len(path.parts) < 3:
            raise ValueError('External source repository path is incomplete')
        public = external_source(path.parts[1])
    elif physical.is_relative_to(execroot):
        public = str(physical.relative_to(execroot))
    elif physical.is_relative_to(sdk):
        public = str(physical.relative_to(sdk))
    elif physical.is_relative_to(external):
        public = external_source(physical.relative_to(external).parts[0])
    elif any(physical.is_relative_to(root.resolve()) for root in (Path('/usr'), Path('/lib'))):
        # Only a basename and digest will enter public_graph for system files.
        return dict(path=str(physical), resolved_path=str(physical), origin='system_headers',
                    **describe(physical))
    else:
        # Do not put an unrecognized private pathname in a public failure log.
        raise ValueError('Source input is outside reviewed source/system roots')
    if public.startswith('external/'):
        origin = public.split('/')[1]
    elif public.startswith('bazel-out/') and '/external/' in public:
        origin = public.split('/external/')[1].split('/')[0]
    else:
        origin = 'sdk_or_generated'
    return dict(path=public, resolved_path=str(physical), origin=origin, **describe(physical))


def trace(probe, execroot, listing, sdk):
    execroot=execroot.resolve(strict=True)
    seen=set();objects=set();responses={};depfiles={};unresolved=[]

    def local(path):
        path=Path(path)
        return path if path.is_absolute() else execroot/path

    def walk(path):
        path=local(path)
        if path in seen:return
        seen.add(path)
        if len(seen)>4096:raise ValueError('Link graph exceeds expected size')
        response=Path(str(path)+'-2.params')
        if not response.is_file():unresolved.append(str(response));return
        responses[str(response.relative_to(execroot))]=describe(response)['sha256']
        for argument in response.read_text().splitlines():
            if argument.startswith('@'):raise ValueError('Nested response file must be captured explicitly')
            if not argument.endswith(('.a','.o')):continue
            item=local(argument)
            if item==path:continue
            if item.suffix=='.a':walk(item)
            else:objects.add(item)

    for alias,path in parse_listing(listing).items():
        if not SYSTEM_NAMES.fullmatch(alias):walk(path)
    walk(probe)
    headers=set()
    for obj in sorted(objects):
        depfile=obj.with_suffix('.d')
        if not depfile.is_file():unresolved.append(str(depfile));continue
        depfiles[str(depfile.relative_to(execroot))]=describe(depfile)['sha256']
        text=depfile.read_text().replace('\\\n',' ')
        if ': ' not in text:raise ValueError('Unsupported depfile grammar')
        headers.update(text.split(': ',1)[1].split())
        if len(headers)>20000:raise ValueError('Source graph exceeds expected size')
    origins=collections.Counter();sources=[]
    for name in sorted(headers):
        item = source_record(name, execroot, sdk)
        origins[item['origin']]+=1
        sources.append(item)
    return {'scope':'Recursive exact link/archive and compiler-depfile inventory; per-action compiler flags require separate action evidence.',
            'response_file_sha256':responses,'dependency_file_sha256':depfiles,
            'object_count':len(objects),'sources':sources,'origin_counts':dict(origins),'unresolved':unresolved,
            'required_action_outputs':{str(path.relative_to(execroot)):describe(path) for path in sorted(seen|objects)}}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('probe','execroot','sdk','dependency-listing','out'):p.add_argument('--'+name,type=Path,required=True)
    args=p.parse_args()
    if args.out.exists():raise ValueError('Fresh output required')
    result=trace(args.probe,args.execroot,args.dependency_listing.read_text(),args.sdk)
    args.out.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({'objects':result['object_count'],'origins':result['origin_counts'],'unresolved':result['unresolved']},indent=2))
    return 2 if result['unresolved'] else 0


if __name__=='__main__':raise SystemExit(main())
