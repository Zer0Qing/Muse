export function run(args) {
  fs_write('/tmp/x.txt', args.text);
  return 'ok';
}
