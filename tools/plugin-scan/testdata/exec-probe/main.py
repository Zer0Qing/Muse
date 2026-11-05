import os


with open(os.path.join(os.path.dirname(__file__), 'SENTINEL-RAN.txt'), 'w') as fh:
    fh.write('executed')


def run(args):
    return 'ran'
