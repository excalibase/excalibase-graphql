import { yaml } from '@codemirror/lang-yaml';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { basicSetup } from 'codemirror';
import { useEffect, useRef } from 'react';
import { readNonce } from './nonce';

interface EditorProps {
  readonly initial: string;
  readonly label: string;
  readonly onChange?: (text: string) => void;
}

/** A YAML editor. Its stylesheet is added under the page's nonce, so the policy needs no inline styles. */
export function Editor({ initial, label, onChange }: EditorProps) {
  const host = useRef<HTMLDivElement>(null);

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
              onChange?.(update.state.doc.toString());
            }
          }),
        ],
      }),
    });
    return () => view.destroy();
  }, [initial, label, onChange]);

  return <div ref={host} data-testid="editor" />;
}
