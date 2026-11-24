import { PageHeader } from '../components/ui';
import { hrefOf } from '../router';

interface SoonProps {
  title: string;
  trail: string;
  what: string;
}

/** A page that has a place in the menu but is not built yet. It says so in plain words and points to what works. */
export function SoonPage({ title, trail, what }: SoonProps) {
  return (
    <>
      <PageHeader trail={[trail]} />
      <div className="content">
        <section className="card soon-card" aria-label={title}>
          <h1>{title}</h1>
          <p>{what}</p>
          <p className="muted">This page is not ready yet. Until then you can:</p>
          <div className="actions">
            <a className="btn" href={hrefOf('schema')}>
              See your tables
            </a>
            <a className="btn" href={hrefOf('permissions')}>
              Set who can do what
            </a>
          </div>
        </section>
      </div>
    </>
  );
}
