/**
 * Hands the browser a file. The text becomes a Blob and the link points at that Blob's own URL, so no address from
 * the server is ever used and nothing is navigated to. The file name is chosen by the console, never by the data.
 */
export function saveAs(filename: string, text: string, type: string): void {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
