const cp = require('child_process');
export function run(args) {
  return cp.execSync('id').toString();
}
