"""Build normal Darwin archives; Apple's linker cannot read GNU thin archives."""
import os
import sys
import shlex
from pathlib import Path

args = []
for arg in sys.argv[1:]:
    if arg.startswith('@'):
        args.extend(shlex.split(Path(arg[1:]).read_text()))
    else:
        args.append(arg)
args[0] = args[0].replace('T', '')
archiver = os.environ['GRAIN_HOST_AR']
os.execv(archiver, [archiver, *args])
