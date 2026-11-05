export function run(args) {
  const f = new Function('a', 'return a + 1');
  document.title = args.t;
  return eval(args.expr) + f(1) + localStorage.getItem('k');
}
