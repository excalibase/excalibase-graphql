// Edits a YAML permissions document in place: the grid and the token form change the tree, and this carries just those
// changes into the text, so comments and the person's own layout stay. It never reads YAML for meaning: the engine's
// /parse says what a text means, and the caller compares that with the tree this was meant to produce.
import { Document, isMap, isSeq, parseDocument, visit, type Node, type YAMLSeq } from 'yaml';
import type { Tree } from './docmodel';

type Path = (string | number)[];
type Plain = Record<string, unknown>;

// Words and numbers a YAML 1.1 reader turns into something other than text. They are written quoted, so every reader
// agrees on what they are.
const ONE_ONE_WORD = /^(y|n|yes|no|true|false|on|off|null|~)$/i;
const ONE_ONE_NUMBER = /^[-+]?(0b[01_]+|0x[0-9a-f_]+|0[0-7_]*|\d[\d_]*(\.\d*)?([e][-+]?\d+)?|\.\d+|\.inf|\.nan|\d+(:[0-5]?\d)+(\.\d*)?)$/i;
const DATE = /^\d{4}-\d\d-\d\d/;

const isPlain = (value: unknown): value is Plain => value !== null && typeof value === 'object' && !Array.isArray(value);

export function treesEqual(left: unknown, right: unknown): boolean {
  if (Array.isArray(left) || Array.isArray(right)) {
    return Array.isArray(left) && Array.isArray(right) && left.length === right.length
      && left.every((item, index) => treesEqual(item, right[index]));
  }
  if (isPlain(left) && isPlain(right)) {
    const keys = Object.keys(left);
    return keys.length === Object.keys(right).length && keys.every((key) => key in right && treesEqual(left[key], right[key]));
  }
  return Object.is(left, right);
}

function nodeFor(doc: Document, value: unknown): Node {
  const node = doc.createNode(value);
  visit(node, {
    Scalar(_key, scalar) {
      const { value: held } = scalar;
      if (typeof held === 'string' && (held === '' || ONE_ONE_WORD.test(held) || ONE_ONE_NUMBER.test(held) || DATE.test(held))) {
        scalar.type = 'QUOTE_DOUBLE';
      }
    },
  });
  return node;
}

const ruleKey = (rule: unknown): string => (isPlain(rule) ? String(rule.table) + '\u0000' + String(rule.role) : '');

/** The rules are matched by table and role, so a rule that is edited keeps its comments and one that is not is untouched. */
function syncRules(doc: Document, before: unknown[], after: unknown[]): void {
  const path: Path = ['tables'];
  const kept = new Map(after.map((rule) => [ruleKey(rule), rule]));
  const known = new Set(before.map(ruleKey));
  before.forEach((rule, index) => {
    const wanted = kept.get(ruleKey(rule));
    if (wanted !== undefined) {
      sync(doc, [...path, index], rule, wanted);
    }
  });
  for (let index = before.length - 1; index >= 0; index--) {
    if (!kept.has(ruleKey(before[index]))) {
      doc.deleteIn([...path, index]);
    }
  }
  const added = after.filter((rule) => !known.has(ruleKey(rule)));
  if (added.length > 0) {
    const list = doc.getIn(path, true);
    if (isSeq(list)) {
      (list as YAMLSeq).flow = false;
    }
    added.forEach((rule) => doc.addIn(path, nodeFor(doc, rule)));
  }
}

function sync(doc: Document, path: Path, before: unknown, after: unknown): void {
  if (treesEqual(before, after)) {
    return;
  }
  if (isPlain(before) && isPlain(after)) {
    Object.keys(before).filter((key) => !(key in after)).forEach((key) => doc.deleteIn([...path, key]));
    Object.keys(after).forEach((key) => {
      if (key in before) {
        sync(doc, [...path, key], before[key], after[key]);
      } else {
        doc.setIn([...path, key], nodeFor(doc, after[key]));
      }
    });
  } else if (Array.isArray(before) && Array.isArray(after) && path.length === 1 && path[0] === 'tables') {
    syncRules(doc, before, after);
  } else {
    doc.setIn(path, nodeFor(doc, after));
  }
}

/**
 * The text with the changes between what it holds and the new tree carried in, or null when the text is not a YAML map
 * that can be edited this way. The caller must still check what the engine reads from the result.
 */
export function patchYaml(text: string, next: Tree): string | null {
  const doc = parseDocument(text, { version: '1.1', keepSourceTokens: false });
  if (doc.errors.length > 0 || !isMap(doc.contents)) {
    return null;
  }
  const current = doc.toJS() as Plain;
  if (treesEqual(current, next)) {
    return text;
  }
  sync(doc, [], current, next);
  return doc.toString({ lineWidth: 0, flowCollectionPadding: false });
}
