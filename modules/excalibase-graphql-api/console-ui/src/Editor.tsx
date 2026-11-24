import { yaml } from '@codemirror/lang-yaml';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { basicSetup } from 'codemirror';
import { useEffect, useRef } from 'react';
import { readNonce } from './nonce';

interface EditorProps {
  readonly value: string;
  readonly label: string;
  readonly readOnly?: boolean;
  readonly onChange?: (text: string) => void;
}

// The editor takes its colours from the page's tokens, so light and dark follow the page and no inline style is needed.
const THEME = EditorView.theme({
  '&': { backgroundColor: 'var(--surface)', color: 'var(--text)', fontSize: '13px' },
  '.cm-content': { fontFamily: 'var(--mono)', caretColor: 'var(--text)' },
  '.cm-gutters': { backgroundColor: 'var(--surface-2)', color: 'var(--text-2)', borderRight: '1px solid var(--border)' },
  '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: 'var(--accent-row)' },
  '&.cm-focused': { outline: '2px solid var(--focus)' },
  '.cm-cursor': { borderLeftColor: 'var(--text)' },
});

/** A YAML editor. Its stylesheet is added under the page's nonce, so the policy needs no inline styles. */
export function Editor({ value, label, readOnly = false, onChange }: EditorProps) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const handler = useRef(onChange);
  handler.current = onChange;

  useEffect(() => {
    if (!host.current) {
      return undefined;
    }
    const created = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: value,
        extensions: [
          basicSetup,
          yaml(),
          THEME,
          EditorState.readOnly.of(readOnly),
          EditorView.cspNonce.of(readNonce()),
          EditorView.contentAttributes.of({ 'aria-label': label }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) {
              handler.current?.(update.state.doc.toString());
            }
          }),
        ],
      }),
    });
    view.current = created;
    return () => {
      created.destroy();
      view.current = null;
    };
    // The editor is built once; later text arrives through the effect below, so typing never rebuilds it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [label, readOnly]);

  useEffect(() => {
    const current = view.current;
    if (current && current.state.doc.toString() !== value) {
      current.dispatch({ changes: { from: 0, to: current.state.doc.length, insert: value } });
    }
  }, [value]);

  return <div ref={host} className="editor" data-testid="editor" />;
}
