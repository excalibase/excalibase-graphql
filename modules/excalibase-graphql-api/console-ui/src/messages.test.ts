import { describe, expect, test } from 'vitest';
import { ApiError } from './api/adminApi';
import { classify, describeFailure, restoreFailure } from './messages';

const failure = (status: number, body: unknown = {}) => new ApiError(status, 'code', body);

describe('the words for what the engine answered', () => {
  test.each([
    [409, 'conflict'], [422, 'invalid'], [405, 'readonly'], [428, 'missing'], [500, 'refused'], [502, 'unreachable'], [418, 'failed'],
  ])('a %i answer to a save is %s', (status, kind) => {
    expect(classify(failure(status, { currentVersion: 'v9', errors: ['bad'] })).kind).toBe(kind);
  });

  test('a conflict carries the version the engine is at, an invalid text its reasons', () => {
    expect(classify(failure(409, { currentVersion: 'v9' }))).toEqual({ kind: 'conflict', currentVersion: 'v9' });
    expect(classify(failure(422, { errors: ['surprise'] }))).toEqual({ kind: 'invalid', errors: ['surprise'] });
  });

  test('a known code gets a sentence, an unknown one is named', () => {
    expect(describeFailure(new ApiError(401, 'unauthorized', null))).toBe('That secret was not accepted.');
    expect(describeFailure(new ApiError(500, 'weird', null))).toBe('The request failed (weird).');
  });

  test('a refused restore says why in the engine words', () => {
    expect(restoreFailure(failure(422, { errors: ['x'] }))).toMatchObject({ kind: 'error', errors: ['x'] });
    expect(restoreFailure(failure(404)).text).toContain('no longer in the history');
  });
});
