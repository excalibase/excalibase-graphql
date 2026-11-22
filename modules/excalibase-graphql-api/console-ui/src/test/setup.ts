// jsdom has no modal dialog: give it the two methods a <dialog> needs, so the console's own dialogs can be driven.
const proto = (typeof HTMLDialogElement === 'undefined' ? {} : HTMLDialogElement.prototype) as HTMLDialogElement & { showModal?: () => void; close?: () => void };
if (typeof proto.showModal !== 'function') {
  proto.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  proto.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open');
    this.dispatchEvent(new Event('close'));
  };
}

if (typeof Range !== 'undefined') {
// CodeMirror measures text with ranges, which jsdom does not lay out: an empty answer is all it needs.
const empty = { x: 0, y: 0, width: 0, height: 0, top: 0, right: 0, bottom: 0, left: 0, toJSON: () => ({}) };
Range.prototype.getClientRects = () => ({ length: 0, item: () => null, [Symbol.iterator]: function* () { /* none */ } }) as unknown as DOMRectList;
Range.prototype.getBoundingClientRect = () => empty as DOMRect;
}
