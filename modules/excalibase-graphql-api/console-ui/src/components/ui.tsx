import { useEffect, useRef, type ReactNode } from 'react';

export function ErrorNote({ children }: { readonly children: ReactNode }) {
  return <div role="alert" className="rounded-md border border-danger/30 bg-danger/5 p-3 text-danger">{children}</div>;
}

export function PageTitle({ title, hint }: { readonly title: string; readonly hint?: string }) {
  return (
    <div className="mb-4">
      <h1 className="text-xl font-semibold">{title}</h1>
      {hint ? <p className="hint mt-1">{hint}</p> : null}
    </div>
  );
}

interface ModalProps {
  readonly id: string;
  readonly describedBy: string;
  readonly onCancel: () => void;
  /** The control that has the focus when the dialog opens: the safe answer. */
  readonly focus: 'cancel';
  readonly children: ReactNode;
}

/** A modal <dialog>. Escape asks to close it, which is a cancel; the safe button has the focus when it opens. */
export function Modal({ id, describedBy, onCancel, children }: ModalProps) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    if (!element) {
      return;
    }
    element.showModal();
    element.querySelector<HTMLElement>('[data-initial-focus]')?.focus();
  }, []);
  return (
    <dialog
      ref={dialog}
      id={id}
      aria-describedby={describedBy}
      onCancel={(event) => { event.preventDefault(); onCancel(); }}
      className="m-auto w-full max-w-lg rounded-lg border border-line bg-surface p-5 text-ink shadow-xl backdrop:bg-black/40"
    >
      {children}
    </dialog>
  );
}
