export interface UploadPolicy {
  readonly maxBytes: number;
  readonly allowedMimeTypes: readonly string[];
  readonly allowedExtensions: readonly string[];
}

export interface FileDescriptor {
  readonly id: string;
  readonly accountId: string;
  readonly objectKey: string;
  readonly originalName: string;
  readonly mimeType: string;
  readonly sizeBytes: number;
  readonly sha256: string;
  readonly createdAt: string;
}

export const defaultUploadPolicy: UploadPolicy = {
  maxBytes: 50 * 1024 * 1024,
  allowedMimeTypes: ["text/plain", "text/markdown", "application/pdf", "application/json", "image/jpeg", "image/png", "image/webp", "audio/mpeg", "audio/wav"],
  allowedExtensions: [".txt", ".md", ".markdown", ".pdf", ".json", ".jpg", ".jpeg", ".png", ".webp", ".mp3", ".wav"],
};

function extension(name: string): string { const index = name.lastIndexOf("."); return index < 0 ? "" : name.slice(index).toLowerCase(); }

/** Validate upload metadata before any bytes are written to object storage. */
export function validateUpload(input: { name: string; mimeType: string; sizeBytes: number }, policy = defaultUploadPolicy): void {
  const name = input.name.trim();
  if (!name || name.length > 255 || name.includes("\0") || name.includes("/") || name.includes("\\")) throw new Error("invalid file name");
  if (!Number.isSafeInteger(input.sizeBytes) || input.sizeBytes < 1 || input.sizeBytes > policy.maxBytes) throw new Error("file size exceeds policy");
  if (!policy.allowedMimeTypes.includes(input.mimeType.toLowerCase())) throw new Error("file type is not allowed");
  if (!policy.allowedExtensions.includes(extension(name))) throw new Error("file extension is not allowed");
}

/** Generate an opaque tenant-prefixed object key; original file names never become storage paths. */
export function objectKey(accountId: string, fileId: string, name: string): string {
  if (!accountId || !fileId) throw new Error("accountId and fileId are required");
  return `accounts/${encodeURIComponent(accountId)}/files/${encodeURIComponent(fileId)}${extension(name)}`;
}

export function sanitizeDownloadName(name: string): string { return name.replace(/[\r\n"\\/]/g, "_").slice(0, 255) || "download"; }
