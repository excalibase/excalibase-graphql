import { Editor } from './Editor';

const SAMPLE = `# The permissions document will be edited here.
format: 1
projectId: my-app
tables:
  - table: public.todos
    role: user
    select:
      filter: {}
      columns: "*"
`;

export function App() {
  return (
    <main>
      <h1>Excalibase console</h1>
      <p>
        This is the new console. It is not finished: the working console is at <a href="/console/legacy">/console/legacy</a>.
      </p>
      <Editor initial={SAMPLE} label="Permissions document (preview)" />
    </main>
  );
}
