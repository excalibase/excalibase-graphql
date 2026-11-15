const { observeWithLogs } = require('./engineLogs');

const engine = (lines) => ({ logs: () => lines });
const numbered = (count) => Array.from({ length: count }, (_, index) => `line ${index + 1}`);

describe('the engine side of a case that failed to answer', () => {
  test('a thrown request timeout keeps the last 60 engine log lines, then is thrown again', async () => {
    const written = [];
    const failure = new Error('timeout GET /p/api/v1/customer');

    await expect(observeWithLogs(engine(numbered(100)), async () => { throw failure; }, (text) => written.push(text)))
      .rejects.toBe(failure);

    expect(written).toHaveLength(1);
    expect(written[0]).toContain('timeout GET /p/api/v1/customer');
    expect(written[0]).toContain('line 100');
    expect(written[0]).toContain('line 41');
    expect(written[0]).not.toContain('line 40\n');
  });

  test('a returned error outcome keeps the log too', async () => {
    const written = [];

    const outcome = await observeWithLogs(engine(numbered(3)), async () => ({ error: 'no answer' }), (text) => written.push(text));

    expect(outcome).toEqual({ error: 'no answer' });
    expect(written[0]).toContain('no answer');
    expect(written[0]).toContain('line 3');
  });

  test('an outcome that answered writes nothing', async () => {
    const written = [];

    const outcome = await observeWithLogs(engine(numbered(3)), async () => ({ status: 200 }), (text) => written.push(text));

    expect(outcome).toEqual({ status: 200 });
    expect(written).toEqual([]);
  });
});
