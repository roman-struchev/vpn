// navigator.clipboard only exists in secure contexts (https:// or localhost) —
// it's undefined when the admin/dashboard is served over plain http:// (e.g.
// directly on an IP:port without a TLS reverse proxy), so calling .writeText
// on it throws "Cannot read properties of undefined". Fall back to the
// legacy execCommand('copy') trick, which works without a secure context.
export function copyToClipboard(text: string): boolean {
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text).catch(() => {});
    return true;
  }

  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.focus();
  textarea.select();
  let ok = false;
  try {
    ok = document.execCommand('copy');
  } catch {
    ok = false;
  }
  document.body.removeChild(textarea);
  return ok;
}
