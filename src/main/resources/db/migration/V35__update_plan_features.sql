UPDATE beworking.plans SET
    features = 'Domicilio fiscal y legal,Recepción de correo,Buzón digital,Plataforma BeWorking completa,Acceso reservas BeSpaces,50 consultas MariaAI/mes',
    popular  = TRUE,
    updated_at = NOW()
WHERE plan_key = 'basic';

UPDATE beworking.plans SET
    features = 'Todo en Basic,200 consultas MariaAI/mes,Atención de llamadas,Multi-usuario (3 usuarios),Logo en recepción,Soporte en automatizaciones',
    popular  = FALSE,
    updated_at = NOW()
WHERE plan_key = 'pro';

UPDATE beworking.plans SET
    features = 'Todo en Pro,MariaAI ilimitada,Gestor dedicado,Prioridad en soporte,API access,Features personalizadas',
    popular  = FALSE,
    updated_at = NOW()
WHERE plan_key = 'max';
