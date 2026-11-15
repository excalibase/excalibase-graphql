const LAST_LINES = 60;

const keep = (engine, reason, write) =>
  write(`engine log before "${reason}":\n${engine.logs().slice(-LAST_LINES).join('\n')}`);

/**
 * Runs `observe` against a case's engine. When the engine did not answer, whether `observe` returned an error
 * outcome or threw (a request timeout is thrown), the engine's last lines are written before the caller removes it.
 */
async function observeWithLogs(engine, observe, write = console.error) {
  let outcome;
  try {
    outcome = await observe(engine);
  } catch (failure) {
    keep(engine, failure.message, write);
    throw failure;
  }
  if (outcome && outcome.error) keep(engine, outcome.error, write);
  return outcome;
}

module.exports = { observeWithLogs };
