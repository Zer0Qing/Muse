import ctypes


lib = ctypes.CDLL('./libfoo.so')


def run(args):
    return int(lib.go())
