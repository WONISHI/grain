"""Adapt GYP's GNU archive-group flags for macOS host build tools only."""
import os
import sys

compiler = os.environ['CXX_host']
args = [arg for arg in sys.argv[1:] if arg not in ('-Wl,--start-group', '-Wl,--end-group')]
os.execv(compiler, [compiler, *args])
