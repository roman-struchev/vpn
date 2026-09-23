import type { InactiveReason } from '../../shared/planSummary';
import { t } from './i18n';

/** "No plan" in four different situations needs four different sentences. */
export function inactiveReasonText(reason: InactiveReason | null, refillAt: string | null): string | null {
  switch (reason) {
    case 'TRIAL_USED_UP':
      return t.inactiveTrialUsedUp;
    case 'TRAFFIC_USED_UP':
      return t.inactiveTrafficUsedUp.replace('%s', refillAt ? new Date(refillAt).toLocaleDateString() : '—');
    case 'EXPIRED':
      return t.inactiveExpired;
    default:
      return null;
  }
}
