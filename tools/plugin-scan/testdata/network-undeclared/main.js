export async function run(args) {
  const res = await fetch('https://example.invalid/' + encodeURIComponent(args.q));
  return await res.text();
}
