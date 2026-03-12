package com.beworking.cuentas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CuentaServiceTest {

    @Mock private CuentaRepository cuentaRepository;

    @InjectMocks
    private CuentaService cuentaService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ── 1. REQUIRES_NEW propagation is declared on generateNextInvoiceNumber ──
    //    This is the key fix: the method must run in its own transaction so a JPA
    //    exception here cannot mark the outer booking transaction as rollback-only.
    @Test
    void generateNextInvoiceNumber_hasRequiresNewPropagation() throws Exception {
        Method method = CuentaService.class.getMethod("generateNextInvoiceNumber", Integer.class);
        Transactional tx = method.getAnnotation(Transactional.class);

        assertNotNull(tx, "@Transactional must be present on generateNextInvoiceNumber(Integer)");
        assertEquals(Propagation.REQUIRES_NEW, tx.propagation(),
                "Must use REQUIRES_NEW so a failure here cannot corrupt the outer booking transaction");
    }

    // ── 2. Generates the correct invoice number format ────────────────────────
    @Test
    void generateNextInvoiceNumber_byId_returnsFormattedNumber() {
        Cuenta cuenta = new Cuenta();
        cuenta.setActivo(true);
        cuenta.setPrefijoFactura("PT");
        cuenta.setNumeroSecuencial(41);
        when(cuentaRepository.findById(1)).thenReturn(Optional.of(cuenta));
        when(cuentaRepository.save(any())).thenReturn(cuenta);

        String result = cuentaService.generateNextInvoiceNumber(1);

        assertEquals("PT042", result);
        assertEquals(42, cuenta.getNumeroSecuencial());
    }

    // ── 3. Lookup by codigo delegates to generateNextInvoiceNumber(Integer) ──
    @Test
    void generateNextInvoiceNumber_byCodigo_delegatesToIntegerVersion() {
        Cuenta cuenta = new Cuenta();
        cuenta.setId(5);
        cuenta.setActivo(true);
        cuenta.setPrefijoFactura("GT");
        cuenta.setNumeroSecuencial(99);
        when(cuentaRepository.findByCodigo("GT")).thenReturn(Optional.of(cuenta));
        when(cuentaRepository.findById(5)).thenReturn(Optional.of(cuenta));
        when(cuentaRepository.save(any())).thenReturn(cuenta);

        String result = cuentaService.generateNextInvoiceNumber("GT");

        assertEquals("GT100", result);
    }

    // ── 4. Throws if cuenta not found ─────────────────────────────────────────
    @Test
    void generateNextInvoiceNumber_cuentaNotFound_throwsIllegalArgument() {
        when(cuentaRepository.findById(99)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> cuentaService.generateNextInvoiceNumber(99));
    }

    // ── 5. Throws if cuenta is inactive ──────────────────────────────────────
    @Test
    void generateNextInvoiceNumber_inactiveCuenta_throwsIllegalArgument() {
        Cuenta cuenta = new Cuenta();
        cuenta.setActivo(false);
        when(cuentaRepository.findById(1)).thenReturn(Optional.of(cuenta));

        assertThrows(IllegalArgumentException.class,
                () -> cuentaService.generateNextInvoiceNumber(1));
    }

    // ── 6. Throws if codigo not found ─────────────────────────────────────────
    @Test
    void generateNextInvoiceNumber_codigoNotFound_throwsIllegalArgument() {
        when(cuentaRepository.findByCodigo("XX")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> cuentaService.generateNextInvoiceNumber("XX"));
    }
}
