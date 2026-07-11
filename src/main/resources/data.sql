INSERT INTO customers (full_name, nif, email, iban)
SELECT 'Maria Garcia Lopez', '12345678A', 'maria.garcia@example.com', 'ES9121000418450200051332'
WHERE NOT EXISTS (SELECT 1 FROM customers WHERE nif = '12345678A');

INSERT INTO customers (full_name, nif, email, iban)
SELECT 'Javier Fernandez Ruiz', '23456789B', 'javier.fernandez@example.com', 'ES6621000418401234567891'
WHERE NOT EXISTS (SELECT 1 FROM customers WHERE nif = '23456789B');

INSERT INTO customers (full_name, nif, email, iban)
SELECT 'Lucia Martinez Sanz', '34567890C', 'lucia.martinez@example.com', 'ES7921000813610123456789'
WHERE NOT EXISTS (SELECT 1 FROM customers WHERE nif = '34567890C');

INSERT INTO mortgage_loans (customer_id, property_value, requested_amount, interest_rate, term_years, monthly_payment, status, created_at)
SELECT c.id, 320000.00, 240000.00, 3.10, 25, 1150.63, 'APPROVED', CURRENT_TIMESTAMP
FROM customers c WHERE c.nif = '12345678A'
AND NOT EXISTS (SELECT 1 FROM mortgage_loans WHERE customer_id = c.id);

INSERT INTO mortgage_loans (customer_id, property_value, requested_amount, interest_rate, term_years, monthly_payment, status, created_at)
SELECT c.id, 195000.00, 175000.00, 3.45, 30, 780.95, 'PENDING', CURRENT_TIMESTAMP
FROM customers c WHERE c.nif = '23456789B'
AND NOT EXISTS (SELECT 1 FROM mortgage_loans WHERE customer_id = c.id);

INSERT INTO transfers (customer_id, origin_iban, destination_iban, amount, concept, status, created_at)
SELECT c.id, c.iban, 'ES1000491500051234567892', 850.00, 'Alquiler julio', 'COMPLETED', CURRENT_TIMESTAMP
FROM customers c WHERE c.nif = '12345678A'
AND NOT EXISTS (SELECT 1 FROM transfers WHERE origin_iban = c.iban AND concept = 'Alquiler julio');

INSERT INTO transfers (customer_id, origin_iban, destination_iban, amount, concept, status, created_at)
SELECT c.id, c.iban, 'ES3401281234567890123456', 120.50, 'Factura luz', 'COMPLETED', CURRENT_TIMESTAMP
FROM customers c WHERE c.nif = '34567890C'
AND NOT EXISTS (SELECT 1 FROM transfers WHERE origin_iban = c.iban AND concept = 'Factura luz');
