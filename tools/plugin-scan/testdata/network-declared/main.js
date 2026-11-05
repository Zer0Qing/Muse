export async function run(args) {
  const res = await host.http.get({ url: args.url });
  return res.body;
}
