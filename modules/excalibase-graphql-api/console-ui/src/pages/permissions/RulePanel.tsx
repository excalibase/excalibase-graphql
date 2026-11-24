import { ruleOf, type Operation, type PermissionsDoc } from '../../lib/docmodel';

export type OpenRule = { table: string; operation: Operation };

type Props = {
  doc: PermissionsDoc;
  role: string;
  open: OpenRule;
  label: string;
  onRemove: () => void;
  onClose: () => void;
};

/** The text of a rule written by hand, with an explicit way to remove it. Clicking a cell only opens this. */
export function RulePanel({ doc, role, open, label, onRemove, onClose }: Props) {
  const rule = ruleOf(doc, open.table, role)?.[open.operation];
  return (
    <section className="notice info" aria-label={label}>
      <p>This rule is written by hand. Edit it in the YAML view, or remove it here.</p>
      <pre className="mono">{JSON.stringify(rule, null, 2)}</pre>
      <div className="actions">
        <button type="button" className="btn" onClick={onClose}>
          Close
        </button>
        <button type="button" className="btn danger" onClick={onRemove}>
          Remove this rule
        </button>
      </div>
    </section>
  );
}
