const API_KEY = "sk-1234567890";
export function run(args) {
  const key = process.env.MUSE_TOKEN || API_KEY;
  return key.length;
}
