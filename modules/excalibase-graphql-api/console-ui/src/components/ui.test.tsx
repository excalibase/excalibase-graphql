import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ChangeList, Dialog, Empty, ErrorState, Loading, Warnings } from './ui';

describe('the shared states', () => {
  it('a loading note is announced politely and says what it waits for', () => {
    render(<Loading label="Reading your tables" />);

    expect(screen.getByRole('status')).toHaveTextContent('Reading your tables…');
  });

  it('an error is announced as an alert and offers a retry only when there is one', async () => {
    const retry = vi.fn();
    const { rerender } = render(<ErrorState message="Nope" />);
    expect(screen.getByRole('alert')).toHaveTextContent('Nope');
    expect(screen.queryByRole('button')).toBeNull();

    rerender(<ErrorState message="Nope" onRetry={retry} />);
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(retry).toHaveBeenCalledOnce();
  });

  it('an empty state has a title and a hint', () => {
    render(
      <Empty title="Nothing here">
        <p>Add something.</p>
      </Empty>,
    );

    expect(screen.getByRole('heading', { name: 'Nothing here' })).toBeVisible();
  });

  it('shows no warning box when there are no warnings', () => {
    render(<Warnings warnings={[]} heading="Heads-up" />);

    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('ChangeList', () => {
  it('says when the list could not be built, and when nothing changes', () => {
    const { rerender } = render(<ChangeList changes={null} />);
    expect(screen.getByText('The list of changes could not be built.')).toBeVisible();

    rerender(<ChangeList changes={[]} />);
    expect(screen.getByText('No role or table gets or loses an operation.')).toBeVisible();
  });

  it('marks a gain and a rewritten rule so they stand out', () => {
    render(
      <ChangeList
        changes={[
          { role: 'a', table: 't', gained: ['select'], lost: [], changed: [], text: 'gain line' },
          { role: 'a', table: 't', gained: [], lost: [], changed: ['select'], text: 'change line' },
          { role: 'a', table: 't', gained: [], lost: ['select'], changed: [], text: 'loss line' },
        ]}
      />,
    );

    expect(screen.getByText('gain line')).toHaveClass('gain');
    expect(screen.getByText('change line')).toHaveClass('modify');
    expect(screen.getByText('loss line')).not.toHaveClass('gain');
  });
});

describe('Dialog focus', () => {
  it('gives focus back to the button that opened it when it goes away', async () => {
    function Page({ open, onClose }: { open: boolean; onClose: () => void }) {
      return (
        <>
          <button type="button">Open</button>
          {open ? (
            <Dialog title="Sure?" onClose={onClose}>
              <button type="button" data-autofocus>
                Cancel
              </button>
            </Dialog>
          ) : null}
        </>
      );
    }
    const { rerender } = render(<Page open={false} onClose={() => undefined} />);
    screen.getByRole('button', { name: 'Open' }).focus();

    rerender(<Page open onClose={() => undefined} />);
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    rerender(<Page open={false} onClose={() => undefined} />);

    expect(screen.getByRole('button', { name: 'Open' })).toHaveFocus();
  });
});

describe('Dialog', () => {
  it('opens, gives focus to the marked button, and closes on Escape through the page, not by itself', () => {
    const close = vi.fn();
    render(
      <Dialog title="Sure?" onClose={close}>
        <button type="button" data-autofocus>
          Cancel
        </button>
      </Dialog>,
    );

    expect(screen.getByRole('dialog', { name: 'Sure?' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    screen.getByRole('dialog').dispatchEvent(new Event('cancel', { cancelable: true }));

    expect(close).toHaveBeenCalledOnce();
  });
});
