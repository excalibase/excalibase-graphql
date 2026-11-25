/**
 * The nonce the engine made for this page load, read from the style element it put in the head, through the
 * element's own property: the attribute is hidden from script by the browser, and a meta tag would be readable by
 * anything that can see the page's markup. Empty when there is none (a dev server, a test).
 */
export function readNonce(root: ParentNode = document): string {
  const element = root.querySelector<HTMLStyleElement>('style[nonce]');
  return element?.nonce ?? '';
}
