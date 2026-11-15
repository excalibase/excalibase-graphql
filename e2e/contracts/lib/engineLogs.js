const LAST_LINES = 60;

/** What every thread of the engine was doing when it stopped answering; empty when the engine cannot say. */
const threadsOf = async (engine) => (engine.threadDump ? engine.threadDump() : []);

async function keep(engine, reason, write) {
  write(`engine log before "${reason}":\n${engine.logs().slice(-LAST_LINES).join('\n')}`);
  const threads = await threadsOf(engine).catch((error) => [`no thread dump: ${error.message}`]);
  if (threads.length) write(`engine threads at "${reason}":\n${threads.join('\n')}`);
}

/**
 * Runs `observe` against a case's engine. When the engine did not answer, whether `observe` returned an error
 * outcome or threw (a request timeout is thrown), the engine's last lines are written before the caller removes it.
 */
async function observeWithLogs(engine, observe, write = console.error) {
  let outcome;
  try {
    outcome = await observe(engine);
  } catch (failure) {
    await keep(engine, failure.message, write);
    throw failure;
  }
  if (outcome && outcome.error) await keep(engine, outcome.error, write);
  return outcome;
}

module.exports = { observeWithLogs };
