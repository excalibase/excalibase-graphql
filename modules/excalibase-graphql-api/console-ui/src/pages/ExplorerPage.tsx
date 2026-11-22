import { PageTitle } from '../components/ui';

/** Held for the API explorer step. Until then it says so instead of showing a tool that does not work. */
export function ExplorerPage() {
  return (
    <div>
      <PageTitle title="API explorer" />
      <div className="card p-5">
        <p>Running a query as a role is not built into this console yet.</p>
        <p className="hint mt-1">It arrives in a later step. The data and permissions screens do not depend on it.</p>
      </div>
    </div>
  );
}
