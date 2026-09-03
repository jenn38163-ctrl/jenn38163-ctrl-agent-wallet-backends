const fs = require('fs');
let code = fs.readFileSync('backend/src/server.ts', 'utf8');

code = code.replace(
  `let intentId: string | undefined;`,
  `let intentId: string | undefined;
        let isUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(extOrderId);
        let idCheck = isUuid ? "id=$2" : "provider_ref=$2";`
);

code = code.replace(
  `text:"UPDATE settlement_intents SET status=$1 WHERE id=$2::uuid OR provider_ref=$2 AND status IN ('PENDING', 'SUBMITTED') RETURNING id",`,
  `text:\`UPDATE settlement_intents SET status=$1 WHERE \${idCheck} AND status IN ('PENDING', 'SUBMITTED') RETURNING id\`,`,
);

code = code.replace(
  `const jRes = await db.execute({text:"SELECT journal_entry_id FROM settlement_intents WHERE id=$1::uuid", values:[extOrderId]} as any);`,
  `let isUuidFail = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(extOrderId);
          const jRes = await db.execute({text:\`SELECT journal_entry_id FROM settlement_intents WHERE \${isUuidFail ? "id=$1" : "provider_ref=$1"}\`, values:[extOrderId]} as any);`
);

fs.writeFileSync('backend/src/server.ts', code);
