import { getReplicas } from '../api';
import { Empty, ErrorState, Loading, PageHeader } from '../components/ui';
import { useRequest } from '../session';

export function ReplicasPage() {
  const request = useRequest(getReplicas, []);
  const replicas = request.data?.replicas ?? [];
  return (
    <>
      <PageHeader trail={['Replicas']}>
        <button type="button" className="btn" onClick={request.reload}>
          Refresh
        </button>
      </PageHeader>
      <div className="content">
        <div>
          <h1>Replicas</h1>
          <p className="muted">The copies of the engine that are running. This list shows the replica you are connected to.</p>
        </div>
        {request.status === 'loading' && !request.data ? <Loading label="Loading replicas" /> : null}
        {request.status === 'error' ? <ErrorState message={request.error ?? ''} onRetry={request.reload} /> : null}
        {request.status === 'ready' && replicas.length === 0 ? <Empty title="No replicas reported" /> : null}
        {replicas.length > 0 ? (
          <div className="card flush scroll">
            <table className="tbl">
              <thead>
                <tr>
                  <th scope="col">Replica</th>
                  <th scope="col">Permissions version</th>
                  <th scope="col">State</th>
                  <th scope="col">Source</th>
                </tr>
              </thead>
              <tbody>
                {replicas.map((replica, index) => (
                  <tr key={`${replica.source}-${index}`}>
                    <td>{replica.self ? 'This replica' : 'Another replica'}</td>
                    <td className="mono">{replica.version}</td>
                    <td>
                      <span className={replica.ready ? 'chip all' : 'chip bad'}>{replica.ready ? 'Ready' : 'Not ready'}</span>
                    </td>
                    <td>{replica.source}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </div>
    </>
  );
}
