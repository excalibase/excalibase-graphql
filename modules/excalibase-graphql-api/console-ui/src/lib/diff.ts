// A small line diff for the version history: text in, a list of lines out. It touches no page and no network.

const MAX_CELLS = 4_000_000;
const CONTEXT = 3;

/** A text as lines. A trailing line break ends the last line; it does not start another one. */
function linesOf(text: string): string[] {
  if (text === '') {
    return [];
  }
  const lines = text.split('\n');
  if (lines.at(-1) === '') {
    lines.pop();
  }
  return lines;
}

export type DiffType = 'same' | 'add' | 'del';
export interface DiffLine { type: DiffType; text: string }
export interface Gap { type: 'gap'; count: number }

/** An element of an array whose index is known to be inside it. */
const at = <T>(items: ArrayLike<T>, index: number): T => items[index] as T;

const line = (type: DiffType, text: string): DiffLine => ({ type, text });

function commonLength(first: string[], second: string[], from: (index: number, lines: string[]) => number, to: number): number {
  let length = 0;
  while (length < to && first[from(length, first)] === second[from(length, second)]) {
    length++;
  }
  return length;
}

/** The lines two texts have in common are kept and the rest are removed ('del') or added ('add'), in text order. */
export function lineDiff(oldText: string, newText: string): DiffLine[] {
  const before = linesOf(oldText);
  const after = linesOf(newText);
  const limit = Math.min(before.length, after.length);
  const head = commonLength(before, after, (index) => index, limit);
  const tail = commonLength(before, after, (index, lines) => lines.length - 1 - index, limit - head);
  const middle = diffMiddle(before.slice(head, before.length - tail), after.slice(head, after.length - tail));
  return [
    ...before.slice(0, head).map((text) => line('same', text)),
    ...middle,
    ...before.slice(before.length - tail).map((text) => line('same', text))
  ];
}

/** Longest common subsequence of what lies between the shared head and tail. Too big a middle is shown as replaced. */
function diffMiddle(before: string[], after: string[]): DiffLine[] {
  const removed = () => before.map((text) => line('del', text));
  const added = () => after.map((text) => line('add', text));
  if (before.length === 0 || after.length === 0 || before.length * after.length > MAX_CELLS) {
    return [...removed(), ...added()];
  }
  const width = after.length + 1;
  const reach = new Uint32Array((before.length + 1) * width);
  for (let row = before.length - 1; row >= 0; row--) {
    for (let col = after.length - 1; col >= 0; col--) {
      reach[row * width + col] = at(before, row) === at(after, col)
        ? at(reach, (row + 1) * width + col + 1) + 1
        : Math.max(at(reach, (row + 1) * width + col), at(reach, row * width + col + 1));
    }
  }
  return walk(before, after, reach, width);
}

function walk(before: string[], after: string[], reach: Uint32Array, width: number): DiffLine[] {
  const lines: DiffLine[] = [];
  let row = 0;
  let col = 0;
  while (row < before.length && col < after.length) {
    if (at(before, row) === at(after, col)) {
      lines.push(line('same', at(before, row)));
      row++;
      col++;
    } else if (at(reach, (row + 1) * width + col) >= at(reach, row * width + col + 1)) {
      lines.push(line('del', at(before, row++)));
    } else {
      lines.push(line('add', at(after, col++)));
    }
  }
  before.slice(row).forEach((text) => lines.push(line('del', text)));
  after.slice(col).forEach((text) => lines.push(line('add', text)));
  return lines;
}

/** Keeps the changed lines and a few around each; a run of unchanged lines between is one {type: 'gap', count}. */
export function withContext(lines: DiffLine[], context = CONTEXT): Array<DiffLine | Gap> {
  const near = lines.map((entry, index) => entry.type !== 'same'
    || lines.slice(Math.max(0, index - context), index + context + 1).some((other) => other.type !== 'same'));
  const shown: Array<DiffLine | Gap> = [];
  let hidden = 0;
  lines.forEach((entry, index) => {
    if (near[index]) {
      if (hidden > 0) {
        shown.push({ type: 'gap', count: hidden });
        hidden = 0;
      }
      shown.push(entry);
    } else {
      hidden++;
    }
  });
  return hidden > 0 ? [...shown, { type: 'gap', count: hidden }] : shown;
}
