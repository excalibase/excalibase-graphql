import { yaml } from '@codemirror/lang-yaml';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { basicSetup } from 'codemirror';
import { useEffect, useRef } from 'react';
import { readNonce } from './nonce';

interface EditorProps {
  /** The text the editor starts from. A new one needs a new key: typing is the editor's own, not a prop. */
  readonly initial: string;
  readonly label: string;
  readonly onChange?: (text: string) => void;
}

/** A YAML editor. Its stylesheet is added under the page's nonce, so the policy needs no inline styles. */
export function Editor({ initial, label, onChange }: EditorProps) {
  const host = useRef<HTMLDivElement>(null);
  const changed = useRef(onChange);
  changed.current = onChange;

  useEffect(() => {
    if (!host.current) {
      return undefined;
    }
    const view = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: initial,
        extensions: [
          basicSetup,
          yaml(),
          EditorView.cspNonce.of(readNonce()),
          EditorView.contentAttributes.of({ 'aria-label': label }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) {
              changed.current?.(update.state.doc.toString());
            }
          }),
        ],
      }),
    });
    return () => view.destroy();
    // the editor starts once from `initial`; the owner replaces it with a new key
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return <div ref={host} data-testid="editor" className="overflow-hidden rounded-lg border border-line bg-surface" />;
}
