/** The demo users seeded by Flyway (backend db/demo). Public on purpose: the deployment is a demo. */
export const DEMO_PASSWORD = 'Password1!';

export interface DemoUser {
  username: string;
  role: string;
  icon: string;
  /** one line on the login page */
  hint: string;
  /** what to try, on the landing page */
  tour: string;
}

export const DEMO_USERS: DemoUser[] = [
  {
    username: 'claimant1',
    role: 'Claimant',
    icon: 'person',
    hint: 'reports a loss, answers questions',
    tour: 'Report a car accident and upload a repair estimate, then watch the AI read it within seconds.',
  },
  {
    username: 'adjuster1',
    role: 'Adjuster',
    icon: 'engineering',
    hint: 'handles claims, pays up to ₹5,000',
    tour: 'Work your queue: set a reserve, pay within your ₹5,000 authority, ask the claimant a question.',
  },
  {
    username: 'supervisor1',
    role: 'Supervisor',
    icon: 'verified_user',
    hint: 'approves, reassigns, watches SLAs',
    tour: 'Approve what is above an adjuster\'s limit (never your own), and watch SLA breaches on the dashboard.',
  },
  {
    username: 'siu1',
    role: 'SIU',
    icon: 'policy',
    hint: 'investigates referrals',
    tour: 'Investigate claims referred for fraud and record the outcome; payments stay on hold until you do.',
  },
];
