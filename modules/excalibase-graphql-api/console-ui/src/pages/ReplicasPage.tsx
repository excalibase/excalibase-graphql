import { useQuery } from '@tanstack/react-query';
import { adminApi } from '../api/adminApi';
import { ErrorNote, PageTitle } from '../components/ui';
import { messageOf } from '../messages';

export function ReplicasPage() {
  const replicas = useQuery({ queryKey: ['replicas'], queryFn: () => adminApi.replicas(), retry: false });
  return (
    <div>
      <PageTitle title="Replicas" hint="This replica only. The others are visible through the actuator and the metrics." />
      {replicas.isError ? <ErrorNote>{messageOf(replicas.error)}</ErrorNote> : null}
      {replicas.data ? (
        <div className="card overflow-x-auto">
          <table className="w-full">
            <thead><tr><th className="cell">Replica</th><th className="cell">Version</th><th className="cell">State</th><th className="cell">Source</th></tr></thead>
            <tbody>
              {replicas.data.replicas.map((replica) => (
                <tr key={replica.version + replica.source + String(replica.self)}>
                  <td className="cell">{replica.self ? 'this replica' : 'other'}</td>
                  <td className="cell">{replica.version}</td>
                  <td className="cell">{replica.ready ? 'ready' : 'not ready'}</td>
                  <td className="cell">{replica.source}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  );
}
