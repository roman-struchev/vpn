-- Round annual pricing to whole dollars for cleaner checkout amounts
-- ($9.6 -> $10, $19.2 -> $20; ~17% effective annual discount instead of
-- the exact-20%-of-monthly-x12 figure — see docs/PLAN.md §2).
UPDATE tariffs SET annual_price_usdt_micro = 10000000 WHERE id = 'basic';
UPDATE tariffs SET annual_price_usdt_micro = 20000000 WHERE id = 'pro';
