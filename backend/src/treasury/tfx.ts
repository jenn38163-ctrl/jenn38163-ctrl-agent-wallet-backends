// Treasury Financial Experience (TFX) Rule Registry
// Validates Transactions against U.S. Treasury accounting requirements before settlement

export interface TreasuryClassification {
  tas: string;     // Treasury Account Symbol
  betc: string;    // Business Event Type Code
  alc: string;     // Agency Location Code
  ussgl: string;   // U.S. Standard General Ledger account
}

export class TreasuryPolicyEngine {
  private static readonly RULES = {
    // Standard General Fund mapping for Agent Facility Draws
    FACILITY_DRAW: {
      tas: '020-X-1807-000', // Example: General Fund Account
      betc: 'DISB',          // Disbursement
      alc: '00000000',       // Agency specific
      ussgl: '101000'        // Cash / Fund Balance with Treasury
    },
    FACILITY_REPAYMENT: {
      tas: '020-X-1807-000',
      betc: 'COLL',          // Collection
      alc: '00000000',
      ussgl: '101000'
    }
  };

  /**
   * Evaluates if a proposed transaction meets Treasury reporting criteria 
   * and maps it to the correct CARS accounting classifications.
   */
  static evaluate(transactionType: string, amount: string | number): TreasuryClassification {
    const num = typeof amount === "string" ? parseFloat(amount) : amount;
    if (isNaN(num) || num <= 0) {
      throw new Error("TFX_POLICY_ERROR: Transactions must be > 0");
    }

    if (transactionType === 'FACILITY_DRAW') {
      return this.RULES.FACILITY_DRAW;
    }

    if (transactionType === 'FACILITY_REPAYMENT') {
      return this.RULES.FACILITY_REPAYMENT;
    }

    throw new Error(`TFX_POLICY_ERROR: Unmapped transaction type ${transactionType}`);
  }
}
