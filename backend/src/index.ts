// @ts-nocheck
import express from 'express';
import cors from 'cors';
import dotenv from 'dotenv';
import { PrismaClient } from '@prisma/client';
import { Decimal } from '@prisma/client/runtime/library';

dotenv.config();

const app = express();
const PORT = process.env.PORT || 3000;
const prisma = new PrismaClient();

app.use(cors());
app.use(express.json());

// Healthcheck
app.get('/health', (req, res) => {
  res.json({ status: 'ok', service: 'agent-wallet-backend' });
});

// 1. Gemini Proxy
app.post('/api/copilot/chat', (req, res) => {
  // TODO: Implement authenticated Gemini API proxy
  // Use a secure backend API key, don't expose it to the client
  res.status(501).json({ error: 'Not Implemented' });
});

// 2. Transaction Authorization & Policy Engine
app.post('/api/transactions/authorize', async (req, res) => {
  try {
    const { agentId, amount, vendor, currency } = req.body;
    
    const policy = await prisma.agentPolicy.findUnique({ where: { agentId } });
    if (!policy) {
      return res.status(403).json({ error: 'Policy not found for agent', status: 'DENIED' });
    }

    if (!policy.isActive) {
      return res.status(403).json({ error: 'Agent policy is inactive', status: 'DENIED' });
    }

    if (new Decimal(amount).greaterThan(policy.perTxLimit)) {
      return res.status(403).json({ error: 'Amount exceeds per transaction limit', status: 'DENIED' });
    }

    // TODO: Implement monthly limit summation check here
    
    // Check vendor allowlist
    const allowed = policy.allowedVendors.split(',').map(v => v.trim().toLowerCase());
    if (allowed.length > 0 && !allowed.includes('*') && !allowed.includes(vendor.toLowerCase())) {
      return res.status(403).json({ error: 'Vendor not on allowlist', status: 'DENIED' });
    }

    res.json({ status: 'APPROVED', message: 'Transaction authorized by policy engine' });
  } catch (error) {
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// 3. Complete Agent Transaction Execution Slice
// Agent -> Auth -> Policy Engine -> Transaction Intent -> Ledger -> Audit -> Settlement
app.post('/api/intent/execute', async (req, res) => {
  try {
    const { agentId, vendor, amount, currency = 'USDC', idempotencyKey } = req.body;
    
    if (!idempotencyKey) {
      return res.status(400).json({ error: 'Missing idempotencyKey' });
    }

    // Auth check stub (e.g. check Authorization header)
    const authHeader = req.headers.authorization;
    if (!authHeader || authHeader !== 'Bearer agent-secret-key-stub') {
      return res.status(401).json({ error: 'Unauthorized agent' });
    }

    // Start Transaction Slice
    const result = await prisma.$transaction(async (tx) => {
      // 1. Create or Find Intent (Idempotency)
      let intent = await tx.transactionIntent.findUnique({ where: { idempotencyKey } });
      if (intent) {
        return { status: intent.status, intent, message: 'Returned existing intent' };
      }

      intent = await tx.transactionIntent.create({
        data: { agentId, vendor, amount, currency, status: 'PENDING', idempotencyKey }
      });

      // 2. Policy Engine
      const policy = await tx.agentPolicy.findUnique({ where: { agentId } });
      if (!policy || !policy.isActive || new Decimal(amount).greaterThan(policy.perTxLimit)) {
        await tx.auditLog.create({
          data: {
            transactionIntentId: intent.id, eventType: 'POLICY_CHECK', status: 'FAILURE',
            description: 'Policy denied: Invalid policy or amount exceeded limit'
          }
        });
        await tx.transactionIntent.update({ where: { id: intent.id }, data: { status: 'FAILED' } });
        throw new Error('Policy check failed');
      }

      const allowed = policy.allowedVendors.split(',').map(v => v.trim().toLowerCase());
      if (allowed.length > 0 && !allowed.includes('*') && !allowed.includes(vendor.toLowerCase())) {
        await tx.auditLog.create({
          data: {
            transactionIntentId: intent.id, eventType: 'POLICY_CHECK', status: 'FAILURE',
            description: 'Policy denied: Vendor not on allowlist'
          }
        });
        await tx.transactionIntent.update({ where: { id: intent.id }, data: { status: 'FAILED' } });
        throw new Error('Vendor policy check failed');
      }

      await tx.auditLog.create({
        data: {
          transactionIntentId: intent.id, eventType: 'POLICY_CHECK', status: 'SUCCESS',
          description: 'Policy rules passed'
        }
      });

      // 3. Double-Entry Ledger Posting
      const debitAccountId = `asset:usdc:${agentId}`;
      const creditAccountId = `liability:vendor:${vendor.toLowerCase().replace(/\\s+/g, '_')}`;

      // Ensure accounts exist (stub, usually done out-of-band)
      await tx.account.upsert({
        where: { code: debitAccountId },
        update: {},
        create: { code: debitAccountId, name: `Agent ${agentId} Wallet`, type: 'ASSET' }
      });
      await tx.account.upsert({
        where: { code: creditAccountId },
        update: {},
        create: { code: creditAccountId, name: `Vendor ${vendor}`, type: 'LIABILITY' }
      });

      const entry = await tx.journalEntry.create({
        data: {
          description: `Payment to ${vendor} by ${agentId}`,
          status: 'POSTED',
          referenceId: intent.id,
          postedAt: new Date(),
          postings: {
            create: [
              { accountId: debitAccountId, amount, type: 'DEBIT' },
              { accountId: creditAccountId, amount, type: 'CREDIT' }
            ]
          }
        }
      });

      await tx.auditLog.create({
        data: {
          transactionIntentId: intent.id, eventType: 'LEDGER_POST', status: 'SUCCESS',
          description: `Ledger entry ${entry.id} posted. DR ${debitAccountId} / CR ${creditAccountId}`
        }
      });

      // 4. Settlement Stub
      await tx.auditLog.create({
        data: {
          transactionIntentId: intent.id, eventType: 'SETTLEMENT_BROADCAST', status: 'SUCCESS',
          description: `Settled on-chain for ${amount} ${currency} to ${vendor}`
        }
      });

      intent = await tx.transactionIntent.update({
        where: { id: intent.id },
        data: { status: 'SETTLED' }
      });

      return { status: 'SETTLED', intent, message: 'Transaction fully executed' };
    });

    res.json(result);
  } catch (error) {
    res.status(400).json({ error: (error as Error).message, status: 'FAILED' });
  }
});

// 4. Legacy Settlement Adapters (Starknet/Paynet, Stellar/XLM, MPP, x402, OpenPAYGO)
app.post('/api/settle', (req, res) => {
  // TODO: Route to specific blockchain adapters (Starknet.js, Stellar SDK, etc.)
  res.status(501).json({ error: 'Not Implemented' });
});

// 4. Institutional Double-Entry Ledger (Atomic Balanced Postings)
app.post('/api/ledger/post', async (req, res) => {
  try {
    const { description, referenceId, debitAccountId, creditAccountId, amount } = req.body;

    // Validate atomic posting balance (simplest case: 1 debit, 1 credit of equal amount)
    if (!amount || amount <= 0) {
      return res.status(400).json({ error: 'Invalid amount' });
    }

    // Execute within a database transaction for idempotency and atomicity
    const journalEntry = await prisma.$transaction(async (tx) => {
      // Create the Journal Entry
      const entry = await tx.journalEntry.create({
        data: {
          description,
          status: 'POSTED',
          referenceId,
          postedAt: new Date(),
          postings: {
            create: [
              { accountId: debitAccountId, amount, type: 'DEBIT' },
              { accountId: creditAccountId, amount, type: 'CREDIT' }
            ]
          }
        },
        include: { postings: true }
      });
      return entry;
    });

    res.json({ status: 'success', journalEntry });
  } catch (error) {
    res.status(500).json({ error: 'Failed to post ledger entry', details: (error as Error).message });
  }
});

app.listen(PORT, () => {
  console.log(`Agent Wallet Backend listening on port ${PORT}`);
});
