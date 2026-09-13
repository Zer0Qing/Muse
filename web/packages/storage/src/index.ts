import { DeleteObjectCommand, GetObjectCommand, PutObjectCommand, S3Client } from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";

export interface ObjectStorage {
  createUploadUrl(input: { accountId: string; objectKey: string; mimeType: string; sizeBytes: number }): Promise<{ url: string; expiresAt: string }>;
  createDownloadUrl(input: { accountId: string; objectKey: string; downloadName: string }): Promise<{ url: string; expiresAt: string }>;
  delete(input: { accountId: string; objectKey: string }): Promise<void>;
}

function assertKey(accountId: string, objectKey: string): void {
  if (!accountId || !objectKey.startsWith(`accounts/${encodeURIComponent(accountId)}/files/`) || objectKey.includes("..") || objectKey.includes("\\")) throw new Error("object key is outside account boundary");
}

function contentDisposition(name: string): string { return `attachment; filename="${name.replace(/[\r\n"\\]/g, "_").slice(0, 255)}"`; }

/** S3-compatible storage with tenant-prefixed object validation and short-lived URLs. */
export class S3ObjectStorage implements ObjectStorage {
  constructor(private readonly client: S3Client, private readonly bucket: string, private readonly expiresInSeconds = 900) {
    if (!bucket || expiresInSeconds < 60 || expiresInSeconds > 3600) throw new Error("invalid object storage configuration");
  }
  async createUploadUrl(input: { accountId: string; objectKey: string; mimeType: string; sizeBytes: number }): Promise<{ url: string; expiresAt: string }> {
    assertKey(input.accountId, input.objectKey);
    if (!input.mimeType || !Number.isSafeInteger(input.sizeBytes) || input.sizeBytes < 1) throw new Error("invalid upload metadata");
    const url = await getSignedUrl(this.client, new PutObjectCommand({ Bucket: this.bucket, Key: input.objectKey, ContentType: input.mimeType }), { expiresIn: this.expiresInSeconds });
    return { url, expiresAt: new Date(Date.now() + this.expiresInSeconds * 1000).toISOString() };
  }
  async createDownloadUrl(input: { accountId: string; objectKey: string; downloadName: string }): Promise<{ url: string; expiresAt: string }> {
    assertKey(input.accountId, input.objectKey);
    const url = await getSignedUrl(this.client, new GetObjectCommand({ Bucket: this.bucket, Key: input.objectKey, ResponseContentDisposition: contentDisposition(input.downloadName) }), { expiresIn: this.expiresInSeconds });
    return { url, expiresAt: new Date(Date.now() + this.expiresInSeconds * 1000).toISOString() };
  }
  async delete(input: { accountId: string; objectKey: string }): Promise<void> { assertKey(input.accountId, input.objectKey); await this.client.send(new DeleteObjectCommand({ Bucket: this.bucket, Key: input.objectKey })); }
}

export function createS3ObjectStorage(input: { endpoint: string; region: string; bucket: string; accessKeyId: string; secretAccessKey: string; forcePathStyle?: boolean; expiresInSeconds?: number }): S3ObjectStorage {
  if (!input.endpoint.startsWith("http://") && !input.endpoint.startsWith("https://")) throw new Error("object storage endpoint must be an absolute URL");
  if (!input.accessKeyId || !input.secretAccessKey) throw new Error("object storage credentials are required");
  const client = new S3Client({ endpoint: input.endpoint, region: input.region || "us-east-1", forcePathStyle: input.forcePathStyle ?? true, credentials: { accessKeyId: input.accessKeyId, secretAccessKey: input.secretAccessKey } });
  return new S3ObjectStorage(client, input.bucket, input.expiresInSeconds ?? 900);
}
